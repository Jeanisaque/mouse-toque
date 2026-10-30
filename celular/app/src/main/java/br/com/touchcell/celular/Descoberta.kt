package br.com.touchcell.celular

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Um PC ou TV com o Touch Cell aberto. */
data class Aparelho(val ip: String, val porta: Int, val nome: String, val tipo: String) {
    val url get() = "http://$ip:$porta/"
    fun json(): JSONObject = JSONObject().put("ip", ip).put("porta", porta).put("nome", nome).put("tipo", tipo)

    companion object {
        fun de(j: JSONObject) = Aparelho(j.getString("ip"), j.optInt("porta", 8765), j.optString("nome", j.getString("ip")), j.optString("tipo"))
    }
}

/** Procura aparelhos na rede Wi-Fi e guarda os que já foram usados. */
object Descoberta {
    const val PORTA = 8765
    const val PORTA_BUSCA = 8799

    fun meuIp(): String? = try {
        val lista = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        val ips = lista.flatMap { i -> i.inetAddresses.toList().map { i.name to it } }
            .filter { it.second is Inet4Address && it.second.isSiteLocalAddress }
        (ips.firstOrNull { it.first.startsWith("wlan") } ?: ips.firstOrNull())?.second?.hostAddress
    } catch (e: Exception) { null }

    /** Pergunta a um endereço se ali tem um Touch Cell. */
    fun info(ip: String, porta: Int = PORTA, limite: Int = 700): Aparelho? = try {
        val c = URL("http://$ip:$porta/info").openConnection() as HttpURLConnection
        c.connectTimeout = limite; c.readTimeout = limite
        if (c.responseCode != 200) null else {
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            if (j.optString("app") != "touchcell") null
            else Aparelho(ip, porta, j.optString("nome", ip), j.optString("tipo"))
        }
    } catch (e: Exception) { null }

    /** Pergunta "tem Touch Cell aí?" para a rede toda de uma vez (UDP). O PC 1.13+ responde na hora. */
    private fun perguntarNaRede(meu: String, tipo: String, aoAchar: (Aparelho) -> Unit) {
        val s = try { DatagramSocket() } catch (e: Exception) { return }
        try {
            s.broadcast = true
            s.soTimeout = 400
            val msg = "touchcell?".toByteArray()
            val alvos = listOf("255.255.255.255", meu.substringBeforeLast(".") + ".255")
            val vistos = HashSet<String>()
            val fim = System.currentTimeMillis() + 2000
            var proximoEnvio = 0L
            while (System.currentTimeMillis() < fim) {
                if (System.currentTimeMillis() >= proximoEnvio) {
                    for (a in alvos) try { s.send(DatagramPacket(msg, msg.size, InetAddress.getByName(a), PORTA_BUSCA)) } catch (_: Exception) {}
                    proximoEnvio = System.currentTimeMillis() + 600
                }
                val p = DatagramPacket(ByteArray(1024), 1024)
                val recebeu = try { s.receive(p); true } catch (_: SocketTimeoutException) { false }
                if (!recebeu) continue
                val a = try {
                    val j = JSONObject(String(p.data, 0, p.length))
                    val ip = p.address.hostAddress
                    if (ip != null && j.optString("app") == "touchcell" && j.optString("tipo") == tipo)
                        Aparelho(ip, j.optInt("porta", PORTA), j.optString("nome", ip), tipo)
                    else null
                } catch (_: Exception) { null }
                if (a != null && vistos.add(a.ip)) aoAchar(a)
            }
        } catch (_: Exception) {
        } finally { s.close() }
    }

    /** Pergunta pra rede e varre os endereços (x.x.x.1 a x.x.x.254). Roda fora da thread principal. */
    fun procurar(tipo: String, aoAchar: (Aparelho) -> Unit) {
        val meu = meuIp() ?: return
        val busca = Thread { perguntarNaRede(meu, tipo, aoAchar) }.apply { start() }
        val prefixo = meu.substringBeforeLast(".") + "."
        val pool = Executors.newFixedThreadPool(48)
        for (i in 1..254) {
            val ip = prefixo + i
            pool.execute {
                for (porta in PORTA..PORTA + 1) {
                    val a = info(ip, porta, 600)
                    if (a != null) { if (a.tipo == tipo) aoAchar(a); break }
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(15, TimeUnit.SECONDS)
        busca.join(3000)
    }

    /** O último aparelho mudou de endereço (outro Wi-Fi)? Procura pelo mesmo nome. Para no primeiro que achar. */
    fun reencontrar(antigo: Aparelho): Aparelho? {
        val meu = meuIp() ?: return null
        var achado: Aparelho? = null
        perguntarNaRede(meu, antigo.tipo) { a -> if (achado == null && a.nome == antigo.nome) achado = a }
        return achado
    }

    // ---- aparelhos salvos ----

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("aparelhos", Context.MODE_PRIVATE)

    fun salvos(ctx: Context): List<Aparelho> = try {
        val arr = JSONArray(prefs(ctx).getString("lista", "[]"))
        (0 until arr.length()).map { Aparelho.de(arr.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    fun salvar(ctx: Context, a: Aparelho) {
        val lista = listOf(a) + salvos(ctx).filter { it.ip != a.ip || it.porta != a.porta }
        val arr = JSONArray(); lista.take(10).forEach { arr.put(it.json()) }
        prefs(ctx).edit().putString("lista", arr.toString()).apply()
    }

    fun esquecer(ctx: Context, a: Aparelho) {
        val arr = JSONArray(); salvos(ctx).filter { it.ip != a.ip || it.porta != a.porta }.forEach { arr.put(it.json()) }
        prefs(ctx).edit().putString("lista", arr.toString()).apply()
    }
}

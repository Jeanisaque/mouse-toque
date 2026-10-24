package br.com.touchcell.tv

import android.content.Context
import dadb.AdbKeyPair
import dadb.AdbStream
import dadb.Dadb
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Controle completo": usa a Depuração (ADB) da própria TV para enviar
 * teclas de verdade (setas, OK, voltar, play) e cliques de mouse, que
 * funcionam dentro de qualquer app.
 *
 * Para ser rápido, inicia o [Injetor] uma vez e manda os comandos por ele;
 * se o ajudante não subir, usa o comando "input" (mais lento) como reserva.
 */
class AdbControle(private val ctx: Context) {

    private val comandos = Executors.newSingleThreadExecutor()
    private val conexao = Executors.newSingleThreadExecutor()
    private val tentando = AtomicBoolean(false)

    @Volatile private var dadb: Dadb? = null
    @Volatile private var injetor: AdbStream? = null
    @Volatile var conectado = false
        private set
    @Volatile var ultimoErro: String? = null
        private set

    val rapido: Boolean get() = injetor != null

    private fun chave(): AdbKeyPair {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /** Tenta conectar em segundo plano (não trava os comandos). */
    fun conectar(aoMudar: (() -> Unit)? = null) {
        if ((conectado && injetor != null) || !tentando.compareAndSet(false, true)) return
        conexao.execute {
            try {
                if (!conectado) {
                    val erros = ArrayList<String>()
                    for (host in listOf("127.0.0.1") + ipsLocais()) {
                        try {
                            val d = Dadb.create(host, 5555, chave())
                            d.shell("echo ok")          // aqui a TV pede a permissão na primeira vez
                            dadb = d
                            conectado = true
                            ultimoErro = null
                            break
                        } catch (e: Throwable) {
                            erros.add("$host: " + (e.message ?: e.javaClass.simpleName))
                        }
                    }
                    if (!conectado) ultimoErro = erros.joinToString("\n")
                }
                if (conectado && injetor == null) iniciarInjetor()
            } catch (e: Throwable) {
                ultimoErro = e.message ?: e.javaClass.simpleName
            } finally {
                tentando.set(false)
                aoMudar?.invoke()
            }
        }
    }

    private fun iniciarInjetor() {
        val d = dadb ?: return
        try {
            val s = d.open("shell:CLASSPATH=${ctx.packageCodePath} app_process / br.com.touchcell.tv.Injetor")
            var linha: String?
            do { linha = s.source.readUtf8Line() } while (linha != null && linha.trim() != "pronto")
            if (linha != null) {
                injetor = s
                // lê o que o ajudante responder (erros aparecem na tela da TV)
                Thread {
                    try {
                        while (true) {
                            val l = s.source.readUtf8Line() ?: break
                            if (l.startsWith("erro")) ultimoErro = "Ajudante: $l"
                        }
                    } catch (_: Throwable) {}
                    if (injetor === s) injetor = null
                }.apply { isDaemon = true }.start()
            } else { try { s.close() } catch (_: Throwable) {} }
        } catch (e: Throwable) {
            injetor = null
        }
    }

    /** Escreve no ajudante. Só chamar dentro da fila [comandos]. */
    private fun escrever(linhas: String): Boolean {
        val s = injetor ?: return false
        return try {
            s.sink.writeUtf8(linhas + "\n")
            s.sink.flush()
            true
        } catch (e: Throwable) {
            try { s.close() } catch (_: Throwable) {}
            injetor = null
            conectar()
            false
        }
    }

    private fun shell(cmd: String, senao: () -> Unit) {
        val d = dadb
        if (d == null) { senao(); return }
        try {
            d.shell(cmd)
        } catch (e: Throwable) {
            try { d.close() } catch (_: Throwable) {}
            dadb = null; injetor = null
            conectado = false
            ultimoErro = e.message
            senao()
        }
    }

    fun tecla(codigo: Int, vezes: Int = 1, senao: () -> Unit) {
        if (!conectado) { senao(); conectar(); return }
        val n = vezes.coerceIn(1, 30)
        comandos.execute {
            if (escrever(List(n) { "k $codigo" }.joinToString("\n"))) return@execute
            shell("input keyevent " + List(n) { codigo }.joinToString(" "), senao)
        }
    }

    fun cliqueMouse(x: Int, y: Int, senao: () -> Unit) {
        if (!conectado) { senao(); conectar(); return }
        comandos.execute {
            if (escrever("t $x $y\nc $x $y")) return@execute
            shell("input mouse tap $x $y", senao)
        }
    }

    /** Comando de shell avulso (pela depuração). */
    fun comando(cmd: String) {
        if (!conectado) return
        comandos.execute { shell(cmd) {} }
    }

    /** Rolagem com a roda do mouse (só com o ajudante rápido). */
    fun rolarMouse(x: Int, y: Int, v: Int, h: Int) {
        comandos.execute { escrever("s $x $y $v $h") }
    }

    private fun ipsLocais(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is java.net.Inet4Address && it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
    } catch (e: Exception) { emptyList() }

    fun fechar() {
        try { injetor?.close() } catch (_: Throwable) {}
        try { dadb?.close() } catch (_: Throwable) {}
        injetor = null
        dadb = null
        conectado = false
    }
}

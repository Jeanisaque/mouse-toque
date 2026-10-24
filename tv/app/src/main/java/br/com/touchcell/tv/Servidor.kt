package br.com.touchcell.tv

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

/**
 * Servidor HTTP + WebSocket, igual ao do programa de PC:
 * entrega a página do celular e recebe os comandos.
 */
class Servidor(
    private val ctx: Context,
    private val nomeAparelho: String,
    private val aoComando: (JSONObject, Conexao) -> Unit,
    private val aoMudarConexoes: (Int) -> Unit,
    private val aoConectar: (Conexao) -> Unit = {},
    private val icone: (String) -> ByteArray? = { null },
) {
    companion object {
        const val PORTA_PADRAO = 8765
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val TAG = "TouchCell"

        fun codigo(ctx: Context): String {
            val prefs = ctx.getSharedPreferences("touchcell", Context.MODE_PRIVATE)
            var c = prefs.getString("codigo", null)
            if (c == null || c.length != 4) {
                c = String.format("%04d", SecureRandom().nextInt(10000))
                prefs.edit().putString("codigo", c).apply()
            }
            return c
        }
    }

    @Volatile var porta = PORTA_PADRAO
        private set
    @Volatile private var ativo = false
    private var socketServidor: ServerSocket? = null
    val conexoes = CopyOnWriteArraySet<Conexao>()

    private val pagina: String by lazy {
        ctx.assets.open("celular.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun iniciar() {
        if (ativo) return
        ativo = true
        thread(name = "touchcell-servidor", isDaemon = true) {
            var ss: ServerSocket? = null
            for (p in PORTA_PADRAO until PORTA_PADRAO + 10) {
                try {
                    ss = ServerSocket(p); porta = p; break
                } catch (e: IOException) { /* tenta a próxima */ }
            }
            val servidor = ss ?: run { ativo = false; return@thread }
            socketServidor = servidor
            while (ativo) {
                try {
                    val s = servidor.accept()
                    thread(isDaemon = true) { atender(s) }
                } catch (e: IOException) {
                    if (!ativo) break
                }
            }
        }
    }

    fun parar() {
        ativo = false
        try { socketServidor?.close() } catch (_: IOException) {}
        conexoes.forEach { it.fechar() }
        conexoes.clear()
    }

    fun avisarTodos(msg: JSONObject) {
        val t = msg.toString()
        conexoes.forEach { it.enviar(t) }
    }

    // ------------------------------------------------------------------

    private fun lerCabecalho(entrada: InputStream): String? {
        val buf = StringBuilder()
        var fim = 0
        while (true) {
            val b = entrada.read()
            if (b < 0) return null
            buf.append(b.toChar())
            fim = if ((fim == 0 || fim == 2) && b == '\r'.code) fim + 1
            else if ((fim == 1 || fim == 3) && b == '\n'.code) fim + 1
            else 0
            if (fim == 4) return buf.toString()
            if (buf.length > 65536) return null
        }
    }

    private fun atender(s: Socket) {
        try {
            s.tcpNoDelay = true
            val entrada = BufferedInputStream(s.getInputStream())
            val saida = s.getOutputStream()
            val cab = lerCabecalho(entrada) ?: return s.close()
            val linhas = cab.split("\r\n")
            val partes = linhas[0].split(" ")
            if (partes.size < 2) return s.close()
            val rota = partes[1].substringBefore("?")
            val cabecalhos = HashMap<String, String>()
            for (l in linhas.drop(1)) {
                val i = l.indexOf(':')
                if (i > 0) cabecalhos[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            if (rota == "/ws" && cabecalhos["upgrade"].equals("websocket", ignoreCase = true)) {
                websocket(s, entrada, saida, cabecalhos["sec-websocket-key"] ?: "")
            } else if (rota == "/info") {
                responder(saida, 200, "application/json",
                    JSONObject().put("app", "touchcell").put("tipo", "tv").put("nome", nomeAparelho).toString())
                s.close()
            } else if (rota == "/icone") {
                val nome = java.net.URLDecoder.decode(
                    partes[1].substringAfter("n=", "").substringBefore("&"), "UTF-8")
                val png = if (nome.isNotBlank()) icone(nome) else null
                if (png != null) {
                    saida.write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\n" +
                        "Cache-Control: max-age=86400\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                    saida.write(png); saida.flush()
                } else {
                    responder(saida, 404, "text/plain; charset=utf-8", "sem ícone")
                }
                s.close()
            } else if (rota == "/") {
                responder(saida, 200, "text/html; charset=utf-8", pagina)
                s.close()
            } else {
                responder(saida, 404, "text/plain; charset=utf-8", "Não encontrado")
                s.close()
            }
        } catch (e: Exception) {
            try { s.close() } catch (_: IOException) {}
        }
    }

    private fun responder(saida: OutputStream, cod: Int, tipo: String, corpo: String) {
        val bytes = corpo.toByteArray(Charsets.UTF_8)
        val status = if (cod == 200) "OK" else "Not Found"
        val cab = "HTTP/1.1 $cod $status\r\nContent-Type: $tipo\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        saida.write(cab.toByteArray(Charsets.ISO_8859_1))
        saida.write(bytes)
        saida.flush()
    }

    private fun websocket(s: Socket, entrada: InputStream, saida: OutputStream, chave: String) {
        val sha = MessageDigest.getInstance("SHA-1").digest((chave + GUID).toByteArray(Charsets.ISO_8859_1))
        val aceite = Base64.encodeToString(sha, Base64.NO_WRAP)
        saida.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $aceite\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        saida.flush()

        val con = Conexao(s, DataInputStream(entrada), saida)
        var autorizado = false
        val codigo = codigo(ctx)
        try {
            while (true) {
                val (op, dados) = con.receber() ?: break
                if (op == 0x8) break
                if (op == 0x9) { con.enviarBruto(dados, 0xA); continue }
                if (op != 0x1) continue
                val msg = try { JSONObject(String(dados, Charsets.UTF_8)) } catch (e: Exception) { continue }
                if (!autorizado) {
                    if (msg.optString("t") == "auth" && msg.optString("k") == codigo) {
                        autorizado = true
                        conexoes.add(con)
                        aoMudarConexoes(conexoes.size)
                        con.enviar(JSONObject().put("t", "ok").put("pc", nomeAparelho).put("tv", true).put("susp", 0).toString())
                        aoConectar(con)
                    } else {
                        con.enviar("{\"t\":\"erro\"}")
                        break
                    }
                    continue
                }
                if (msg.optString("t") == "ping") { con.enviar("{\"t\":\"pong\"}"); continue }
                try {
                    aoComando(msg, con)
                } catch (e: Exception) {
                    Log.w(TAG, "Erro ao executar comando", e)
                }
            }
        } catch (e: IOException) {
            // conexão caiu
        } finally {
            con.fechar()
            if (autorizado) {
                conexoes.remove(con)
                aoMudarConexoes(conexoes.size)
            }
        }
    }

    class Conexao(private val s: Socket, private val ent: DataInputStream, private val sai: OutputStream) {
        private val trava = Any()

        fun receber(): Pair<Int, ByteArray>? {
            val b1 = ent.read(); if (b1 < 0) return null
            val b2 = ent.read(); if (b2 < 0) return null
            val op = b1 and 0x0F
            val mascarado = (b2 and 0x80) != 0
            var tam = (b2 and 0x7F).toLong()
            if (tam == 126L) tam = ent.readUnsignedShort().toLong()
            else if (tam == 127L) tam = ent.readLong()
            if (tam < 0 || tam > 1_000_000) throw IOException("quadro grande demais")
            val mascara = ByteArray(4)
            if (mascarado) ent.readFully(mascara)
            val dados = ByteArray(tam.toInt())
            ent.readFully(dados)
            if (mascarado) for (i in dados.indices) dados[i] = (dados[i].toInt() xor mascara[i % 4].toInt()).toByte()
            return Pair(op, dados)
        }

        fun enviar(texto: String) = enviarBruto(texto.toByteArray(Charsets.UTF_8), 0x1)

        fun enviarBruto(dados: ByteArray, op: Int) {
            synchronized(trava) {
                try {
                    val n = dados.size
                    val cab = when {
                        n < 126 -> byteArrayOf((0x80 or op).toByte(), n.toByte())
                        n < 65536 -> byteArrayOf((0x80 or op).toByte(), 126, (n shr 8).toByte(), n.toByte())
                        else -> {
                            val c = ByteArray(10); c[0] = (0x80 or op).toByte(); c[1] = 127
                            for (i in 0 until 8) c[9 - i] = (n.toLong() shr (8 * i)).toByte()
                            c
                        }
                    }
                    sai.write(cab); sai.write(dados); sai.flush()
                } catch (_: IOException) {}
            }
        }

        fun fechar() { try { s.close() } catch (_: IOException) {} }
    }
}

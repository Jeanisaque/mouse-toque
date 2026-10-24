package br.com.touchcell.tv

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Controle completo": usa a Depuração (ADB) da própria TV, pela rede local
 * (127.0.0.1), para enviar teclas de verdade (setas, OK, voltar, play) e
 * cliques de mouse que funcionam dentro de qualquer app.
 *
 * Na primeira vez a TV pergunta "Permitir depuração?" — é só confirmar
 * marcando "Sempre permitir".
 */
class AdbControle(private val ctx: Context) {

    private val comandos = Executors.newSingleThreadExecutor()
    private val conexao = Executors.newSingleThreadExecutor()
    private val tentando = AtomicBoolean(false)

    @Volatile private var dadb: Dadb? = null
    @Volatile var conectado = false
        private set
    @Volatile var ultimoErro: String? = null
        private set

    private fun chave(): AdbKeyPair {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /** Tenta conectar em segundo plano (não trava os comandos). */
    fun conectar(aoMudar: (() -> Unit)? = null) {
        if (conectado || !tentando.compareAndSet(false, true)) return
        conexao.execute {
            try {
                val d = Dadb.create("127.0.0.1", 5555, chave())
                d.shell("echo ok")          // aqui a TV pede a permissão na primeira vez
                dadb = d
                conectado = true
                ultimoErro = null
            } catch (e: Throwable) {
                conectado = false
                ultimoErro = e.message ?: e.javaClass.simpleName
            } finally {
                tentando.set(false)
                aoMudar?.invoke()
            }
        }
    }

    /** Executa um comando de shell. Se não estiver conectado, chama [senao]. */
    fun executar(cmd: String, senao: () -> Unit) {
        val d = dadb
        if (!conectado || d == null) { senao(); conectar(); return }
        comandos.execute {
            try {
                d.shell(cmd)
            } catch (e: Throwable) {
                try { d.close() } catch (_: Throwable) {}
                dadb = null
                conectado = false
                ultimoErro = e.message
                senao()
            }
        }
    }

    fun tecla(codigo: Int, vezes: Int = 1, senao: () -> Unit) {
        val lista = List(vezes.coerceIn(1, 30)) { codigo }.joinToString(" ")
        executar("input keyevent $lista", senao)
    }

    fun cliqueMouse(x: Int, y: Int, senao: () -> Unit) = executar("input mouse tap $x $y", senao)

    fun fechar() {
        try { dadb?.close() } catch (_: Throwable) {}
        dadb = null
        conectado = false
    }
}

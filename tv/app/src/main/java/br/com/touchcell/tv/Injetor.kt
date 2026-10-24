package br.com.touchcell.tv

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Ajudante rápido do Controle completo. Roda dentro da TV (iniciado pelo
 * app através da depuração) e fica lendo comandos, um por linha:
 *   k <código>            aperta e solta uma tecla
 *   c <x> <y>             clique de mouse
 *   s <x> <y> <v> <h>     rolagem do mouse (v/h: -1, 0 ou 1)
 * Assim cada comando chega na hora, sem abrir um processo novo a cada tecla.
 */
object Injetor {
    private lateinit var injetar: (InputEvent) -> Unit

    @JvmStatic
    fun main(args: Array<String>) {
        val im = Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
        val metodo = im.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        injetar = { e -> metodo.invoke(im, e, 0) }

        println("pronto")
        System.out.flush()
        val leitor = System.`in`.bufferedReader()
        while (true) {
            val linha = leitor.readLine() ?: break
            val p = linha.trim().split(" ")
            try {
                when (p[0]) {
                    "k" -> tecla(p[1].toInt())
                    "c" -> clique(p[1].toFloat(), p[2].toFloat())
                    "s" -> rolar(p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat())
                }
            } catch (e: Throwable) {
                println("erro " + e.message)
            }
        }
    }

    private fun tecla(codigo: Int) {
        val t = SystemClock.uptimeMillis()
        for (acao in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            injetar(KeyEvent(t, t, acao, codigo, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        }
    }

    private fun mouse(inicio: Long, acao: Int, x: Float, y: Float, botoes: Int, botaoAcao: Int = 0,
                      vscroll: Float = 0f, hscroll: Float = 0f): MotionEvent {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y; pressure = if (botoes != 0) 1f else 0f; size = 1f
            setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
            setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll)
        }
        val e = MotionEvent.obtain(inicio, SystemClock.uptimeMillis(), acao, 1, arrayOf(props), arrayOf(coords),
            0, botoes, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        if (botaoAcao != 0) {
            try {
                MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType).invoke(e, botaoAcao)
            } catch (_: Throwable) {}
        }
        return e
    }

    private fun clique(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val b = MotionEvent.BUTTON_PRIMARY
        injetar(mouse(t, MotionEvent.ACTION_DOWN, x, y, b))
        injetar(mouse(t, MotionEvent.ACTION_BUTTON_PRESS, x, y, b, b))
        injetar(mouse(t, MotionEvent.ACTION_BUTTON_RELEASE, x, y, 0, b))
        injetar(mouse(t, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun rolar(x: Float, y: Float, v: Float, h: Float) {
        val t = SystemClock.uptimeMillis()
        injetar(mouse(t, MotionEvent.ACTION_SCROLL, x, y, 0, 0, v, h))
    }
}

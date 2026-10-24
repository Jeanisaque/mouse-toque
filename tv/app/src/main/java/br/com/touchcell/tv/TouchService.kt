package br.com.touchcell.tv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Serviço de acessibilidade: mostra o cursor na TV e transforma os
 * comandos do celular em toques, rolagens, voltar/início e texto.
 */
class TouchService : AccessibilityService() {

    companion object {
        @Volatile var instancia: TouchService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var cursor: CursorView? = null
    private var params: WindowManager.LayoutParams? = null
    var servidor: Servidor? = null
        private set

    @Volatile var conectados = 0
        private set

    private var largura = 1920
    private var altura = 1080
    private var x = 960f
    private var y = 540f
    private val escala = 1.4f

    // arrastar e rolar
    private var inicioArrasto: Pair<Float, Float>? = null
    private var rolarY = 0f
    private var rolarX = 0f
    private var rolando = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instancia = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        medirTela()
        x = largura / 2f; y = altura / 2f
        criarCursor()
        val nome = Settings.Global.getString(contentResolver, "device_name")
            ?.takeIf { it.isNotBlank() } ?: "TV ${Build.MODEL}"
        servidor = Servidor(this, nome,
            aoComando = { msg, con -> main.post { tratar(msg, con) } },
            aoMudarConexoes = { n -> main.post { conectados = n; mostrarCursor(n > 0) } }
        ).also { it.iniciar() }
    }

    override fun onDestroy() {
        servidor?.parar()
        cursor?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        instancia = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------------------------------------------------------------- tela

    @Suppress("DEPRECATION")
    private fun medirTela() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            largura = b.width(); altura = b.height()
        } else {
            val p = Point()
            wm.defaultDisplay.getRealSize(p)
            largura = p.x; altura = p.y
        }
    }

    private fun criarCursor() {
        val tam = (altura / 26).coerceAtLeast(28)
        val v = CursorView(this)
        val lp = WindowManager.LayoutParams(
            tam, tam,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = x.toInt(); lp.y = y.toInt()
        v.visibility = View.GONE
        try {
            wm.addView(v, lp)
            cursor = v; params = lp
        } catch (_: Exception) { }
    }

    private fun mostrarCursor(sim: Boolean) {
        cursor?.visibility = if (sim) View.VISIBLE else View.GONE
    }

    private fun moverCursor() {
        val lp = params ?: return
        lp.x = x.toInt(); lp.y = y.toInt()
        try { wm.updateViewLayout(cursor, lp) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- comandos

    private fun tratar(msg: JSONObject, con: Servidor.Conexao) {
        when (msg.optString("t")) {
            "m" -> {
                x = (x + msg.optInt("x") * escala).coerceIn(0f, largura - 1f)
                y = (y + msg.optInt("y") * escala).coerceIn(0f, altura - 1f)
                mostrarCursor(true); moverCursor()
            }
            "c" -> when (msg.optString("b", "l")) {
                "r" -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                "m" -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                else -> tocar(x, y)
            }
            "d" -> if (msg.optString("b", "l") == "l") inicioArrasto = Pair(x, y)
            "u" -> if (msg.optString("b", "l") == "l") {
                val ini = inicioArrasto; inicioArrasto = null
                if (ini != null) {
                    if (hypot(x - ini.first, y - ini.second) < 12f) tocar(x, y)
                    else deslizar(ini.first, ini.second, x, y, 450)
                }
            }
            "s" -> {
                // mesmas unidades da roda do mouse do PC (120 = um "clique" da roda)
                rolarY += msg.optInt("y") * 0.6f
                rolarX += -msg.optInt("x") * 0.6f
                rolar()
            }
            "txt" -> digitar(msg.optString("s"), con)
            "k" -> {
                val n = msg.optInt("n", 1).coerceIn(1, 200)
                tecla(msg.optString("k"), n, con)
            }
            "brilho_ler", "brilho" -> con.enviar("{\"t\":\"brilho\",\"v\":null}")
            "suspender", "cancelar_susp" -> con.enviar("{\"t\":\"susp\",\"resta\":0}")
        }
    }

    // ---- achar o elemento da tela que está embaixo da setinha ----

    private fun maisFundo(n: AccessibilityNodeInfo, px: Int, py: Int): AccessibilityNodeInfo {
        val r = Rect()
        for (i in n.childCount - 1 downTo 0) {
            val c = n.getChild(i) ?: continue
            if (!c.isVisibleToUser) continue
            c.getBoundsInScreen(r)
            if (r.contains(px, py)) return maisFundo(c, px, py)
        }
        return n
    }

    private fun noAlvo(px: Float, py: Float, filtro: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val ix = px.toInt(); val iy = py.toInt()
        val raizes = ArrayList<AccessibilityNodeInfo>()
        try {
            windows.sortedByDescending { it.layer }.forEach { w -> w.root?.let { raizes.add(it) } }
        } catch (_: Exception) {}
        if (raizes.isEmpty()) rootInActiveWindow?.let { raizes.add(it) }
        val r = Rect()
        for (raiz in raizes) {
            raiz.getBoundsInScreen(r)
            if (!r.contains(ix, iy)) continue
            var n: AccessibilityNodeInfo? = maisFundo(raiz, ix, iy)
            while (n != null) {
                if (filtro(n)) return n
                n = n.parent
            }
        }
        return null
    }

    private fun temAcao(n: AccessibilityNodeInfo, id: Int) = n.actionList.any { it.id == id }

    private fun tocar(px: Float, py: Float) {
        // 1º: clicar no elemento embaixo da setinha (funciona em TV sem tela de toque)
        val alvo = noAlvo(px, py) { it.isClickable || temAcao(it, AccessibilityNodeInfo.ACTION_CLICK) }
        if (alvo != null) {
            if (alvo.isFocusable && !alvo.isFocused) alvo.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (alvo.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        }
        // 2º: toque simulado na tela
        val p = Path().apply { moveTo(px, py) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        dispatchGesture(g, null, null)
    }

    private fun deslizar(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long, fim: (() -> Unit)? = null) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms))
            .build()
        val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { fim?.invoke() }
            override fun onCancelled(d: GestureDescription?) { fim?.invoke() }
        }, null)
        if (!ok) fim?.invoke()
    }

    private fun rolar() {
        if (rolando) return
        val passo = 60f
        if (abs(rolarY) >= passo || abs(rolarX) >= passo) {
            val vertical = abs(rolarY) >= abs(rolarX)
            val valor = if (vertical) rolarY else rolarX
            val lista = noAlvo(x, y) { it.isScrollable }
            if (lista != null) {
                // dedo para baixo/direita = voltar o conteúdo (como no celular)
                val voltar = valor > 0
                val especifica = when {
                    vertical && voltar -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
                    vertical -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
                    voltar -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
                    else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
                }
                val acao = if (temAcao(lista, especifica.id)) especifica.id
                    else if (voltar) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                if (vertical) rolarY -= passo * Math.signum(valor) else rolarX -= passo * Math.signum(valor)
                if (vertical) rolarX = 0f else rolarY = 0f
                lista.performAction(acao)
                rolando = true
                main.postDelayed({ rolando = false; rolar() }, 110)
                return
            }
        }
        val dy = rolarY.coerceIn(-altura * 0.4f, altura * 0.4f)
        val dx = rolarX.coerceIn(-largura * 0.4f, largura * 0.4f)
        if (abs(dy) < 25f && abs(dx) < 25f) return
        rolarY -= dy; rolarX -= dx
        val y1 = y.coerceIn(altura * 0.1f, altura * 0.9f)
        val x1 = x.coerceIn(largura * 0.1f, largura * 0.9f)
        val y2 = (y1 + dy).coerceIn(1f, altura - 2f)
        val x2 = (x1 + dx).coerceIn(1f, largura - 2f)
        rolando = true
        deslizar(x1, y1, x2, y2, 120) {
            main.post { rolando = false; rolar() }
        }
    }

    private fun campoFocado(): AccessibilityNodeInfo? {
        val n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return if (n != null && n.isEditable) n else null
    }

    private fun textoAtual(n: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && n.isShowingHintText) return ""
        return n.text?.toString() ?: ""
    }

    private fun definirTexto(n: AccessibilityNodeInfo, t: String) {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t)
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun avisar(con: Servidor.Conexao, texto: String) {
        con.enviar(JSONObject().put("t", "aviso").put("s", texto).toString())
    }

    private fun digitar(texto: String, con: Servidor.Conexao) {
        if (texto.isEmpty()) return
        val n = campoFocado()
        if (n == null) {
            avisar(con, "Clique primeiro num campo de texto na TV")
            return
        }
        definirTexto(n, textoAtual(n) + texto)
    }

    private fun midia(codigo: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, codigo, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, codigo, 0))
    }

    private fun volume(direcao: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direcao, AudioManager.FLAG_SHOW_UI)
    }

    private fun tecla(k: String, n: Int, con: Servidor.Conexao) {
        when (k) {
            "esc" -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "win" -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "backspace" -> {
                val campo = campoFocado()
                if (campo != null) definirTexto(campo, textoAtual(campo).dropLast(n))
                else performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            "enter" -> {
                val campo = campoFocado()
                if (campo != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    campo.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else {
                    tocar(x, y)
                }
            }
            "volup" -> repeat(n) { volume(AudioManager.ADJUST_RAISE) }
            "voldown" -> repeat(n) { volume(AudioManager.ADJUST_LOWER) }
            "mute" -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
            "playpause" -> midia(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "next" -> midia(KeyEvent.KEYCODE_MEDIA_NEXT)
            "prev" -> midia(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            else -> avisar(con, "Essa tecla não funciona na TV")
        }
    }

    // ---------------------------------------------------------------- cursor

    private class CursorView(ctx: Context) : View(ctx) {
        private val preenchimento = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val borda = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
        }
        private val seta = Path()

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val s = w / 24f
            borda.strokeWidth = 1.6f * s
            seta.reset()
            seta.moveTo(2 * s, 1 * s)
            seta.lineTo(2 * s, 19 * s)
            seta.lineTo(7 * s, 14.5f * s)
            seta.lineTo(10.5f * s, 22 * s)
            seta.lineTo(14 * s, 20.5f * s)
            seta.lineTo(10.5f * s, 13 * s)
            seta.lineTo(17 * s, 13 * s)
            seta.close()
        }

        override fun onDraw(c: Canvas) {
            c.drawPath(seta, preenchimento)
            c.drawPath(seta, borda)
        }
    }
}

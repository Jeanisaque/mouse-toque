package br.com.touchcell.tv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
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

        /** Tenta cada tecla de energia e para na primeira que realmente apagar a tela (ok = tecla que funcionou). */
        private const val APAGAR_TELA =
            "acordada(){ dumpsys power | grep -q 'mWakefulness=Awake'; }; ok=''; " +
            "for k in 177 26 223; do input keyevent \$k; sleep 3; if ! acordada; then ok=\$k; break; fi; done"
        private const val ACENDER_TELA =
            "input keyevent 224; sleep 2; acordada || input keyevent \$ok; sleep 2; acordada || input keyevent 26"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var cursor: CursorView? = null
    private var params: WindowManager.LayoutParams? = null
    var servidor: Servidor? = null
        private set
    var adb: AdbControle? = null
        private set

    @Volatile var conectados = 0
        private set
    private var modoMouse = false

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
            aoMudarConexoes = { n -> main.post { conectados = n; mostrarCursor(n > 0 && modoMouse) } },
            aoConectar = { con -> avisarAtualizacao(con); con.enviar(statusDesligar().toString()) },
            icone = { nome -> iconeApp(nome) }
        ).also { it.iniciar() }
        verificarAtualizacao()
        adb = AdbControle(this).also { it.conectar() }
        manterAdb()
    }

    /** Tenta reconectar o Controle completo de tempos em tempos, se cair. */
    private fun manterAdb() {
        main.postDelayed({
            val a = adb
            if (a != null && (!a.conectado || !a.rapido) && conectados > 0) a.conectar()
            manterAdb()
        }, 30_000L)
    }

    private fun verificarAtualizacao() {
        Thread {
            Atualizador.verificar(this)
            main.postDelayed({ verificarAtualizacao() }, 6 * 60 * 60 * 1000L)   // de 6 em 6 horas
        }.start()
    }

    private fun avisarAtualizacao(con: Servidor.Conexao) {
        if (Atualizador.temNova(this)) {
            avisar(con, "Tem versão nova do Touch Cell para a TV: abra o app na TV para atualizar")
        } else if (adb?.conectado != true) {
            avisar(con, "Para as setas funcionarem dentro dos apps, ative o Controle completo no app da TV")
        }
    }

    override fun onDestroy() {
        adb?.fechar()
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
            "modo" -> {
                modoMouse = msg.optBoolean("mouse")
                mostrarCursor(conectados > 0 && modoMouse)
            }
            "m" -> {
                modoMouse = true
                x = (x + msg.optInt("x") * escala).coerceIn(0f, largura - 1f)
                y = (y + msg.optInt("y") * escala).coerceIn(0f, altura - 1f)
                mostrarCursor(true); moverCursor()
            }
            "c" -> when (msg.optString("b", "l")) {
                "r" -> performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                "m" -> irParaInicio()
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
            "app" -> abrirApp(msg.optString("n"), con)
            "brilho_ler", "brilho" -> con.enviar("{\"t\":\"brilho\",\"v\":null}")
            "suspender" -> agendarDesligar(msg.optInt("min", 0))
            "cancelar_susp" -> cancelarDesligar()
            "turbo_ler" -> turbo.estado { con.enviar(it.toString()) }
            "turbo_anim" -> turbo.animacoes(msg.optDouble("v", 1.0).toFloat()) {
                turbo.estado { con.enviar(it.toString()) }
            }
            "turbo_protecao" -> turbo.protecao(msg.optBoolean("on")) {
                turbo.estado { con.enviar(it.toString()) }
            }
            "turbo_limpar" -> turbo.limpar { txt ->
                con.enviar(JSONObject().put("t", "aviso").put("s", txt).toString())
                turbo.estado { con.enviar(it.toString()) }
            }
            "turbo_app" -> turbo.app(msg.optString("p"), msg.optBoolean("on")) {
                turbo.estado { con.enviar(it.toString()) }
            }
        }
    }

    private val turbo by lazy { Turbo(this) { adb } }

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
        val alvo = noAlvo(px, py) { it.isClickable || temAcao(it, AccessibilityNodeInfo.ACTION_CLICK) }
        if (alvo != null) {
            if (alvo.isFocusable && !alvo.isFocused) alvo.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (alvo.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { som("ok"); return }
        }
        val a = adb
        if (a != null && a.conectado) {
            a.cliqueMouse(px.toInt(), py.toInt()) { main.post { tocarSemAdb(px, py) } }
            som("ok")
        } else tocarSemAdb(px, py)
    }

    private fun tocarSemAdb(px: Float, py: Float) {
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
        val a = adb
        if (a != null && a.conectado) {
            // rolar = setas de verdade (funcionam em qualquer app)
            val passoSeta = 90f
            while (abs(rolarY) >= passoSeta || abs(rolarX) >= passoSeta) {
                if (abs(rolarY) >= abs(rolarX)) {
                    val s = Math.signum(rolarY)
                    rolarY -= passoSeta * s
                    a.tecla(if (s > 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, 1) {}
                } else {
                    val s = Math.signum(rolarX)
                    rolarX -= passoSeta * s
                    a.tecla(if (s > 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT, 1) {}
                }
            }
            return
        }
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
        var n = campoFocado()
        if (n == null) {
            n = rootInActiveWindow?.let { primeiro(it) { c -> c.isEditable } }
            n?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        if (n == null) {
            avisar(con, "Selecione primeiro um campo de texto na TV")
            return
        }
        definirTexto(n, textoAtual(n) + texto)
    }

    // ---------------------------------------------------------------- direcional (seleção)

    private fun focado(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isVisibleToUser }

    private fun focaveis(n: AccessibilityNodeInfo, lista: MutableList<AccessibilityNodeInfo>) {
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (!c.isVisibleToUser) continue
            if (c.isFocusable && c.isEnabled) lista.add(c)
            focaveis(c, lista)
        }
    }

    private fun primeiro(raiz: AccessibilityNodeInfo, filtro: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val fila = ArrayDeque<AccessibilityNodeInfo>(); fila.add(raiz)
        while (fila.isNotEmpty()) {
            val n = fila.removeFirst()
            if (n.isVisibleToUser && filtro(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { fila.add(it) }
        }
        return null
    }

    private fun primeiroFocavel(raiz: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val lista = ArrayList<AccessibilityNodeInfo>(); focaveis(raiz, lista)
        val r = Rect()
        return lista.minByOrNull { it.getBoundsInScreen(r); r.top * 10000 + r.left }
    }

    /** Procura, pela posição na tela, o item selecionável mais próximo na direção pedida. */
    private fun vizinho(raiz: AccessibilityNodeInfo, atual: AccessibilityNodeInfo, dir: String): AccessibilityNodeInfo? {
        val lista = ArrayList<AccessibilityNodeInfo>(); focaveis(raiz, lista)
        val a = Rect(); atual.getBoundsInScreen(a)
        val r = Rect()
        var melhor: AccessibilityNodeInfo? = null
        var melhorNota = Float.MAX_VALUE
        for (n in lista) {
            if (n == atual) continue
            n.getBoundsInScreen(r)
            if (r.isEmpty || r.contains(a)) continue
            val dx = (r.centerX() - a.centerX()).toFloat()
            val dy = (r.centerY() - a.centerY()).toFloat()
            val (prim, sec, ok) = when (dir) {
                "right" -> Triple(dx, abs(dy), r.left >= a.centerX())
                "left" -> Triple(-dx, abs(dy), r.right <= a.centerX())
                "down" -> Triple(dy, abs(dx), r.top >= a.centerY())
                else -> Triple(-dy, abs(dx), r.bottom <= a.centerY())
            }
            if (!ok || prim <= 0f) continue
            val nota = prim + 2.5f * sec
            if (nota < melhorNota) { melhorNota = nota; melhor = n }
        }
        return melhor
    }

    private fun moverFoco(dir: String) {
        val raiz = rootInActiveWindow ?: return
        val atual = focado()
        if (atual == null) {
            primeiroFocavel(raiz)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            return
        }
        val d = when (dir) { "up" -> View.FOCUS_UP; "down" -> View.FOCUS_DOWN; "left" -> View.FOCUS_LEFT; else -> View.FOCUS_RIGHT }
        val prox = try { atual.focusSearch(d) } catch (e: Exception) { null }
        if (prox != null && prox != atual && prox.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return
        val geo = vizinho(raiz, atual, dir)
        if (geo != null && geo.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return
        // chegou na borda de uma lista: rola e tenta de novo
        var p: AccessibilityNodeInfo? = atual
        while (p != null && !p.isScrollable) p = p.parent
        if (p != null) {
            val acao = if (dir == "up" || dir == "left") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            if (p.performAction(acao)) {
                main.postDelayed({
                    val f = focado() ?: return@postDelayed
                    val raiz2 = rootInActiveWindow ?: return@postDelayed
                    (try { f.focusSearch(d) } catch (e: Exception) { null } ?: vizinho(raiz2, f, dir))
                        ?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }, 180)
            }
        }
    }

    private fun clicarFocado() {
        val f = focado()
        if (f == null) {
            rootInActiveWindow?.let { primeiroFocavel(it) }?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            return
        }
        if (f.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        var n: AccessibilityNodeInfo? = f.parent
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            n = n.parent
        }
        val r = Rect(); f.getBoundsInScreen(r)
        tocar(r.exactCenterX(), r.exactCenterY())
    }

    // ---------------------------------------------------------------- apps, busca, configurações

    private fun abrir(i: Intent, con: Servidor.Conexao?): Boolean {
        return try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); true
        } catch (e: Exception) {
            con?.let { avisar(it, "A TV não deixou abrir isso") }; false
        }
    }

    private fun abrirBusca(con: Servidor.Conexao) {
        val opcoes = listOf(
            Intent("android.search.action.GLOBAL_SEARCH"),
            Intent(Intent.ACTION_ASSIST),
            Intent(Intent.ACTION_WEB_SEARCH)
        )
        for (i in opcoes) if (abrir(i, null)) return
        avisar(con, "Não encontrei a busca da TV")
    }

    private fun acharApp(nome: String): android.content.pm.ResolveInfo? {
        val pm = packageManager
        val lista = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0) +
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        return lista.firstOrNull { it.loadLabel(pm).toString().contains(nome, ignoreCase = true) }
    }

    private val cacheIcones = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** Ícone (de preferência o banner de TV) do app instalado, em PNG, para o celular mostrar. */
    private fun iconeApp(nome: String): ByteArray? {
        cacheIcones[nome]?.let { return it }
        val info = acharApp(nome) ?: return null
        val pm = packageManager
        val ai = info.activityInfo
        val banner = ai.loadBanner(pm) ?: pm.getApplicationBanner(ai.applicationInfo)
        val d = banner ?: ai.loadIcon(pm) ?: return null
        val (w, h) = if (banner != null) Pair(240, 135) else Pair(96, 96)
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        val saida = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, saida)
        return saida.toByteArray().also { cacheIcones[nome] = it }
    }

    private fun abrirApp(nome: String, con: Servidor.Conexao) {
        if (nome.isBlank()) return
        val pm = packageManager
        val achado = acharApp(nome)
        if (achado == null) { avisar(con, "$nome não está instalado na TV"); return }
        val pacote = achado.activityInfo.packageName
        val i = pm.getLeanbackLaunchIntentForPackage(pacote) ?: pm.getLaunchIntentForPackage(pacote)
        if (i == null || !abrir(i, con)) avisar(con, "Não consegui abrir $nome")
    }

    // ---------------------------------------------------------------- desligar com temporizador

    @Volatile private var fimDesligar = 0L
    private val desligarAgendado = Runnable { fimDesligar = 0L; avisarDesligar(); desligarAgora() }
    private val avisoUmMinuto = Runnable {
        android.widget.Toast.makeText(this, "Touch Cell TV: a TV vai desligar em 1 minuto", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun statusDesligar(): JSONObject {
        val resta = if (fimDesligar == 0L) 0 else ((fimDesligar - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        return JSONObject().put("t", "susp").put("resta", resta)
    }

    private fun avisarDesligar() { servidor?.avisarTodos(statusDesligar()) }

    private fun agendarDesligar(min: Int) {
        main.removeCallbacks(desligarAgendado); main.removeCallbacks(avisoUmMinuto)
        val ms = if (min <= 0) 1500L else min.coerceAtMost(600) * 60_000L
        fimDesligar = System.currentTimeMillis() + ms
        main.postDelayed(desligarAgendado, ms)
        if (min >= 2) main.postDelayed(avisoUmMinuto, ms - 60_000L)
        avisarDesligar()
    }

    private fun cancelarDesligar() {
        main.removeCallbacks(desligarAgendado); main.removeCallbacks(avisoUmMinuto)
        fimDesligar = 0L
        avisarDesligar()
    }

    /** Tenta desligar de vários jeitos, conferindo se a tela realmente apagou. */
    private fun desligarAgora() {
        val a = adb
        if (a != null && a.conectado) {
            // pela depuração: testa TV Power, Power e Dormir, conferindo se a tela apagou;
            // se a TV ignorar todas, desliga o sistema (como tirar da tomada, mas com segurança)
            servidor?.avisarTodos(JSONObject().put("t", "aviso").put("s", "Desligando a TV…"))
            a.executar(APAGAR_TELA + "; echo \"tecla=\$ok\"") { saida ->
                val tecla = Regex("tecla=(\\S*)").find(saida ?: "")?.groupValues?.get(1).orEmpty()
                if (tecla.isNotEmpty()) return@executar
                servidor?.avisarTodos(JSONObject().put("t", "aviso").put("s", "A TV ignorou as teclas; desligando pelo sistema…"))
                a.comando("svc power shutdown")
            }
            return
        }
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val tentativas = listOf<() -> Unit>(
            { teclaReal(KeyEvent.KEYCODE_SLEEP, 1) {} },
            { teclaReal(KeyEvent.KEYCODE_POWER, 1) {} },
            { adb?.comando("input keyevent 223; input keyevent 26") },
            { if (Build.VERSION.SDK_INT >= 28) performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }
        )
        fun passo(i: Int) {
            if (!pm.isInteractive) return                 // já desligou
            if (i >= tentativas.size) {
                android.widget.Toast.makeText(this, "Touch Cell TV: não consegui desligar a TV", android.widget.Toast.LENGTH_LONG).show()
                servidor?.avisarTodos(JSONObject().put("t", "aviso").put("s", "A TV não aceitou o comando de desligar"))
                return
            }
            val nomes = listOf("tecla Dormir", "tecla Power", "comando de desligar", "bloquear a tela")
            val txt = "Desligando a TV: tentativa ${i + 1} de ${tentativas.size} (${nomes[i]})" +
                if (adb?.conectado == true) "" else " · Controle completo desligado"
            android.widget.Toast.makeText(this, "Touch Cell TV: $txt", android.widget.Toast.LENGTH_SHORT).show()
            servidor?.avisarTodos(JSONObject().put("t", "aviso").put("s", txt))
            tentativas[i]()
            main.postDelayed({ passo(i + 1) }, 3000)
        }
        passo(0)
    }

    /**
     * Apaga a tela e acende de novo (para quando a imagem some mas o som continua).
     * Tudo roda dentro da TV num comando só, então funciona mesmo se a rede cair no meio.
     */
    private fun reiniciarTela(con: Servidor.Conexao) {
        val a = adb
        if (a == null || !a.conectado) {
            con.enviar(JSONObject().put("t", "aviso").put("s", "Precisa do Controle completo ativo na TV").toString())
            return
        }
        con.enviar(JSONObject().put("t", "aviso").put("s", "Reiniciando a imagem… (até 15 segundos)").toString())
        a.executar(APAGAR_TELA + "; if [ -n \"\$ok\" ]; then sleep 2; " + ACENDER_TELA + "; fi; echo \"tecla=\$ok\"") { saida ->
            val tecla = Regex("tecla=(\\S*)").find(saida ?: "")?.groupValues?.get(1).orEmpty()
            val txt = if (tecla.isEmpty()) "A TV ignorou as teclas de energia (testei TV Power, Power e Dormir)"
                      else "Imagem reiniciada (tecla ${nomeTecla(tecla)})"
            servidor?.avisarTodos(JSONObject().put("t", "aviso").put("s", txt))
        }
    }

    private fun nomeTecla(k: String) = when (k) { "177" -> "TV Power"; "26" -> "Power"; "223" -> "Dormir"; else -> k }

    /** Sempre volta para a tela inicial da TV (launcher). */
    private fun irParaInicio() {
        try {
            val i = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(i)
        } catch (e: Exception) {
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
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

    /** Tecla de verdade pelo Controle completo (ADB); sem ele, usa o plano B. */
    private fun teclaReal(codigo: Int, n: Int, planoB: () -> Unit) {
        val a = adb
        if (a != null && a.conectado) a.tecla(codigo, n) { main.post(planoB) }
        else planoB()
    }

    /** Som de navegação da própria TV (o mesmo do controle). */
    private fun som(tipo: String) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val efeito = when (tipo) {
            "up" -> AudioManager.FX_FOCUS_NAVIGATION_UP
            "down" -> AudioManager.FX_FOCUS_NAVIGATION_DOWN
            "left" -> AudioManager.FX_FOCUS_NAVIGATION_LEFT
            "right" -> AudioManager.FX_FOCUS_NAVIGATION_RIGHT
            else -> AudioManager.FX_KEY_CLICK
        }
        try { am.playSoundEffect(efeito) } catch (_: Exception) {}
    }

    private fun tecla(k: String, n: Int, con: Servidor.Conexao) {
        when (k) {
            "esc" -> teclaReal(KeyEvent.KEYCODE_BACK, 1) { performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
            "win" -> irParaInicio()
            "up", "down", "left", "right" -> {
                val codigo = when (k) {
                    "up" -> KeyEvent.KEYCODE_DPAD_UP; "down" -> KeyEvent.KEYCODE_DPAD_DOWN
                    "left" -> KeyEvent.KEYCODE_DPAD_LEFT; else -> KeyEvent.KEYCODE_DPAD_RIGHT
                }
                teclaReal(codigo, n) { repeat(n) { moverFoco(k) } }
                som(k)
            }
            "ok" -> { teclaReal(KeyEvent.KEYCODE_DPAD_CENTER, 1) { clicarFocado() }; som("ok") }
            "mic" -> abrirBusca(con)
            "config" -> abrir(Intent(Settings.ACTION_SETTINGS), con)
            "tela" -> reiniciarTela(con)
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
            "playpause" -> teclaReal(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 1) { midia(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
            "next" -> teclaReal(KeyEvent.KEYCODE_MEDIA_NEXT, 1) { midia(KeyEvent.KEYCODE_MEDIA_NEXT) }
            "prev" -> teclaReal(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 1) { midia(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
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

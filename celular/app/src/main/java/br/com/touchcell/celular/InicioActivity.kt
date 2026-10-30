package br.com.touchcell.celular

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/** Tela inicial: lista de PCs/TVs, busca na rede e atualização do app. */
class InicioActivity : Activity() {

    private val tipo = BuildConfig.TIPO
    private val nomeTipo get() = if (tipo == "tv") "TV" else "PC"
    private lateinit var lista: LinearLayout
    private lateinit var estado: TextView
    private lateinit var girando: ProgressBar
    private lateinit var aviso: TextView
    private val achados = LinkedHashMap<String, Aparelho>()
    @Volatile private var procurando = false
    @Volatile private var atualizando = false

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun texto(tam: Float, cor: String, negrito: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, tam); setTextColor(Color.parseColor(cor))
        if (negrito) typeface = Typeface.DEFAULT_BOLD
    }

    private fun fundo(cor: String, borda: String? = null, raio: Int = 16) = GradientDrawable().apply {
        setColor(Color.parseColor(cor)); cornerRadius = dp(raio).toFloat()
        if (borda != null) setStroke(dp(1), Color.parseColor(borda))
    }

    private fun botao(rotulo: String, principal: Boolean = false, acao: () -> Unit) = Button(this).apply {
        text = rotulo; isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(Color.WHITE)
        background = if (principal) fundo("#4F8CFF") else fundo("#1F232C", "#2B303B")
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { acao() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0F1115")
        window.navigationBarColor = Color.parseColor("#0F1115")

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(16))
        }
        raiz.addView(texto(28f, "#E9EBF1", true).apply { text = "Touch Cell $nomeTipo" })
        raiz.addView(texto(15f, "#8B93A4").apply {
            text = if (tipo == "tv") "Controle remoto da sua TV pelo celular" else "Mouse e teclado do seu PC pelo celular"
            setPadding(0, dp(4), 0, dp(18))
        })
        aviso = texto(15f, "#8FB4FF").apply { visibility = View.GONE; setPadding(0, 0, 0, dp(8)) }
        raiz.addView(aviso)

        val linhaEstado = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        girando = ProgressBar(this).apply { layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) } }
        estado = texto(15f, "#A9B0BF")
        linhaEstado.addView(girando); linhaEstado.addView(estado)
        raiz.addView(linhaEstado)

        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        raiz.addView(lista)

        raiz.addView(botao("Procurar de novo") { procurar() })
        raiz.addView(botao("Digitar o endereço") { digitarEndereco() })
        raiz.addView(texto(13.5f, "#8B93A4").apply {
            text = if (tipo == "tv")
                "Na TV, abra o app Touch Cell e deixe a TV e o celular no mesmo Wi-Fi."
            else
                "No PC, abra o Touch Cell (Iniciar Touch Cell) e deixe o PC e o celular no mesmo Wi-Fi."
            setPadding(0, dp(16), 0, 0)
        })

        val espaco = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) }
        raiz.addView(espaco)
        raiz.addView(texto(13f, "#8B93A4").apply {
            text = "Criado pelos irmãos Joel Andrade e Isaque Andrade"
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#0F1115"))
            addView(raiz)
        })

        mostrarLista()
        // ao abrir o app, conecta direto no último aparelho usado (se ele responder)
        if (savedInstanceState == null) tentarUltimo() else procurar()
    }

    override fun onRestart() {
        super.onRestart()
        mostrarLista()
        if (achados.isEmpty()) procurar()
    }

    override fun onResume() {
        super.onResume()
        procurarAtualizacao()
    }

    private fun tentarUltimo() {
        val ultimo = Descoberta.salvos(this).firstOrNull { it.tipo == tipo }
        if (ultimo == null) { procurar(automatico = true); return }
        estado.text = "Conectando em ${ultimo.nome}…"; girando.visibility = View.VISIBLE
        thread {
            // mesmo endereço de antes; se não responder (outro Wi-Fi), procura pelo nome
            val ok = Descoberta.info(ultimo.ip, ultimo.porta, 1500) ?: Descoberta.reencontrar(ultimo)
            runOnUiThread { if (ok != null) abrir(ok) else procurar(automatico = true) }
        }
    }

    /** automatico: se achar um aparelho só, já conecta sem precisar tocar. */
    private fun procurar(automatico: Boolean = false) {
        if (procurando) return
        procurando = true
        girando.visibility = View.VISIBLE
        estado.text = "Procurando ${if (tipo == "tv") "TVs" else "PCs"} na rede…"
        thread {
            Descoberta.procurar(tipo) { a -> runOnUiThread { achados[a.ip + ":" + a.porta] = a; mostrarLista() } }
            runOnUiThread {
                procurando = false
                girando.visibility = View.GONE
                if (automatico && achados.size == 1) { abrir(achados.values.first()); return@runOnUiThread }
                estado.text = if (achados.isEmpty())
                    "Nenhum${if (tipo == "tv") "a TV" else " PC"} encontrad${if (tipo == "tv") "a" else "o"}. Confira se o Touch Cell está aberto e no mesmo Wi-Fi." +
                        if (tipo == "pc") "\n\nNo PC, clique em \"Liberar no Firewall\" na página do Touch Cell." else ""
                else "Toque para conectar:"
            }
        }
    }

    private fun mostrarLista() {
        lista.removeAllViews()
        val salvos = Descoberta.salvos(this).filter { it.tipo == tipo }
        val todos = LinkedHashMap<String, Pair<Aparelho, Boolean>>()
        achados.values.forEach { todos[it.ip + ":" + it.porta] = it to true }
        salvos.forEach { val k = it.ip + ":" + it.porta; if (k !in todos) todos[k] = it to false }
        for ((a, online) in todos.values) {
            lista.addView(Button(this).apply {
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                text = (if (online) "●  " else "○  ") + a.nome + "\n" + a.ip + if (online) "" else "  (não encontrado agora)"
                setTextColor(Color.parseColor(if (online) "#E9EBF1" else "#8B93A4"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                background = fundo("#171A21", if (online) "#2F4570" else "#2B303B")
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(8) }
                setOnClickListener { abrir(a) }
                setOnLongClickListener {
                    AlertDialog.Builder(this@InicioActivity)
                        .setMessage("Esquecer ${a.nome}?")
                        .setPositiveButton("Esquecer") { _, _ -> Descoberta.esquecer(this@InicioActivity, a); achados.remove(a.ip + ":" + a.porta); mostrarLista() }
                        .setNegativeButton("Cancelar", null).show()
                    true
                }
            })
        }
    }

    private fun abrir(a: Aparelho) {
        Descoberta.salvar(this, a)
        startActivity(Intent(this, ControleActivity::class.java).putExtra("url", a.url).putExtra("nome", a.nome))
    }

    private fun digitarEndereco() {
        val campo = EditText(this).apply {
            hint = "Ex.: 192.168.0.111"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Endereço d${if (tipo == "tv") "a TV" else "o PC"}")
            .setMessage("Aparece na tela d${if (tipo == "tv") "a TV" else "o PC"}, embaixo do QR code.")
            .setView(campo)
            .setPositiveButton("Conectar") { _, _ ->
                val txt = campo.text.toString().trim().removePrefix("http://").substringBefore("/")
                val ip = txt.substringBefore(":")
                val porta = txt.substringAfter(":", Descoberta.PORTA.toString()).toIntOrNull() ?: Descoberta.PORTA
                estado.text = "Conectando em $ip…"
                thread {
                    val a = Descoberta.info(ip, porta, 2500)
                    runOnUiThread {
                        if (a != null) abrir(a) else estado.text = "Não encontrei o Touch Cell em $ip. Ele está aberto?"
                    }
                }
            }
            .setNegativeButton("Cancelar", null).show()
    }

    // ---------------------------------------------------------------- atualização

    private fun procurarAtualizacao() {
        if (atualizando) return
        thread {
            if (!Atualizador.verificar(this)) return@thread
            runOnUiThread {
                aviso.visibility = View.VISIBLE
                aviso.text = "Versão nova disponível (${Atualizador.nomePublicado}). Toque aqui para atualizar."
                aviso.setOnClickListener { atualizar() }
            }
        }
    }

    private fun atualizar() {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            aviso.text = "Permita que o Touch Cell instale atualizações e toque aqui de novo."
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            return
        }
        if (atualizando) return
        atualizando = true
        aviso.text = "Baixando…"
        thread {
            val ok = try {
                Atualizador.baixarEInstalar(this) { pct -> runOnUiThread { aviso.text = "Baixando… $pct%" } }
            } catch (e: Exception) { false }
            runOnUiThread {
                aviso.text = if (ok) "Confirme a instalação na tela que abriu." else "Não consegui baixar agora. Tente de novo mais tarde."
                atualizando = false
            }
        }
    }
}

package br.com.protecaofamilia.painel

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.firebase.firestore.ListenerRegistration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var raiz: LinearLayout
    private var escuta: ListenerRegistration? = null

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun texto(tam: Float, cor: String, negrito: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, tam); setTextColor(Color.parseColor(cor))
        if (negrito) typeface = Typeface.DEFAULT_BOLD
    }

    private fun botao(rotulo: String, principal: Boolean = true, acao: () -> Unit) = Button(this).apply {
        text = rotulo; isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = GradientDrawable().apply {
            setColor(Color.parseColor(if (principal) "#39D98A" else "#1F232C")); cornerRadius = dp(14).toFloat()
        }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { acao() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.iniciar(this)
        window.statusBarColor = Color.parseColor("#0F1115")
        desenhar()
    }

    override fun onDestroy() {
        super.onDestroy()
        escuta?.remove()
    }

    private fun desenhar() {
        raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(36), dp(24), dp(24)) }
        raiz.addView(texto(24f, "#E9EBF1", true).apply { text = "Proteção Família" })
        raiz.addView(texto(14f, "#8B93A4").apply { text = "Painel da família"; setPadding(0, dp(4), 0, dp(16)) })

        if (Prefs.codigoFamilia.isBlank()) telaInicial() else telaLista()

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#0F1115"))
            addView(raiz)
        })
    }

    // ------------------------------------------------------------ primeira vez

    private fun telaInicial() {
        raiz.addView(botao("Criar família") {
            FamiliaRepo.criarFamilia { codigo ->
                runOnUiThread {
                    if (codigo != null) {
                        Prefs.codigoFamilia = codigo
                        desenhar()
                    } else {
                        AlertDialog.Builder(this).setMessage("Não consegui criar agora. Confira a internet.").setPositiveButton("OK", null).show()
                    }
                }
            }
        })
        raiz.addView(botao("Já tenho um código", principal = false) { entrarComCodigo() })
        raiz.addView(texto(13f, "#6B7280").apply {
            text = "Crie a família aqui e depois cole o código no app instalado no celular do seu filho."
            setPadding(0, dp(16), 0, 0)
        })
    }

    private fun entrarComCodigo() {
        val campo = EditText(this).apply { hint = "Código da família" }
        AlertDialog.Builder(this).setTitle("Entrar na família").setView(campo)
            .setPositiveButton("Entrar") { _, _ ->
                val codigo = CodigoUtil.normalizar(campo.text.toString())
                FamiliaRepo.entrarNaFamilia(codigo) { ok ->
                    runOnUiThread {
                        if (ok) { Prefs.codigoFamilia = codigo; desenhar() }
                        else AlertDialog.Builder(this).setMessage("Código não encontrado.").setPositiveButton("OK", null).show()
                    }
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    // ------------------------------------------------------------ lista de celulares

    private fun telaLista() {
        raiz.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(texto(14f, "#8FB4FF").apply {
                text = "Código da família: ${Prefs.codigoFamilia}"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Button(this@MainActivity).apply {
                text = "Copiar"; isAllCaps = false; background = null
                setTextColor(Color.parseColor("#8FB4FF"))
                setOnClickListener {
                    val gerenciador = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    gerenciador.setPrimaryClip(ClipData.newPlainText("codigo", Prefs.codigoFamilia))
                }
            })
        })

        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
        raiz.addView(lista)
        raiz.addView(texto(13f, "#6B7280").apply {
            text = "Toque num celular pra ver o mapa, tocar o alarme ou travar a tela."
            setPadding(0, dp(16), 0, 0)
        })

        escuta?.remove()
        escuta = FamiliaRepo.escutarDispositivos(Prefs.codigoFamilia) { dispositivos ->
            runOnUiThread {
                lista.removeAllViews()
                if (dispositivos.isEmpty()) {
                    lista.addView(texto(14f, "#8B93A4").apply { text = "Nenhum celular vinculado ainda." })
                }
                dispositivos.forEach { d -> lista.addView(cartao(d)) }
            }
        }
    }

    private fun cartao(d: Dispositivo) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(Color.parseColor("#171A21")); cornerRadius = dp(16).toFloat() }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
        addView(texto(17f, "#E9EBF1", true).apply { text = (if (d.alarmeAtivo) "🔔 " else "") + d.nome })
        val bateria = if (d.bateria >= 0) "${d.bateria}%" else "?"
        val quando = d.atualizadoEm?.let { SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(it)) } ?: "nunca"
        addView(texto(13f, "#8B93A4").apply { text = "Bateria: $bateria · Atualizado: $quando" })
        setOnClickListener {
            startActivity(Intent(this@MainActivity, DetalheActivity::class.java).putExtra("id", d.id).putExtra("nome", d.nome))
        }
    }
}

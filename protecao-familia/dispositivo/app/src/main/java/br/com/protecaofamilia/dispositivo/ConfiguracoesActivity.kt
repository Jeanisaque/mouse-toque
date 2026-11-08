package br.com.protecaofamilia.dispositivo

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Tela de engrenagem: protegida por PIN (quem abriu já digitou o PIN na MainActivity). */
class ConfiguracoesActivity : Activity() {

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.iniciar(this)
        window.statusBarColor = Color.parseColor("#0F1115")

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(24))
        }
        raiz.addView(TextView(this).apply {
            text = "Configurações"
            setTextColor(Color.parseColor("#E9EBF1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        })
        raiz.addView(TextView(this).apply {
            text = "Celular: ${Prefs.nomeDispositivo}\nCódigo da família: ${Prefs.codigoFamilia}"
            setTextColor(Color.parseColor("#8B93A4"))
            setPadding(0, dp(16), 0, dp(16))
        })
        raiz.addView(linha("Compartilhar código da família") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, Prefs.codigoFamilia)
            }, "Compartilhar código"))
        })
        raiz.addView(linha("Testar alarme") { AlarmeActivity.disparar(this) })
        raiz.addView(linha("Trocar PIN") { trocarPin() })
        raiz.addView(linha("Desativar proteção", perigo = true) { confirmarDesativar() })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F1115"))
            addView(raiz)
        })
    }

    private fun linha(rotulo: String, perigo: Boolean = false, acao: () -> Unit) = Button(this).apply {
        text = rotulo; isAllCaps = false
        setTextColor(if (perigo) Color.parseColor("#FF6B6B") else Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#1F232C")); cornerRadius = dp(14).toFloat()
        }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { acao() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    private fun trocarPin() {
        val atual = EditText(this).apply {
            hint = "PIN atual"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle("PIN atual").setView(atual).setPositiveButton("Continuar") { _, _ ->
            if (!Prefs.verificarPin(atual.text.toString())) {
                AlertDialog.Builder(this).setMessage("PIN incorreto.").setPositiveButton("OK", null).show()
                return@setPositiveButton
            }
            val novo = EditText(this).apply {
                hint = "Novo PIN"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            AlertDialog.Builder(this).setTitle("Novo PIN").setView(novo).setPositiveButton("Salvar") { _, _ ->
                if (novo.text.toString().length in 4..6) {
                    Prefs.definirPin(novo.text.toString())
                    AlertDialog.Builder(this).setMessage("PIN alterado.").setPositiveButton("OK", null).show()
                } else {
                    AlertDialog.Builder(this).setMessage("O PIN precisa ter de 4 a 6 números.").setPositiveButton("OK", null).show()
                }
            }.show()
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun confirmarDesativar() {
        val campo = EditText(this).apply {
            hint = "Digite o PIN para confirmar"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Desativar proteção")
            .setMessage("Isso para o envio de localização e remove este celular da família. Quem cuida dele vai ser avisado.")
            .setView(campo)
            .setPositiveButton("Desativar") { _, _ ->
                if (Prefs.verificarPin(campo.text.toString())) {
                    desativar()
                } else {
                    AlertDialog.Builder(this).setMessage("PIN incorreto.").setPositiveButton("OK", null).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun desativar() {
        FamiliaRepo.enviarAlerta(
            Prefs.codigoFamilia, Prefs.idDispositivo,
            "protecao_desativada", "Desativado pelo PIN, no próprio celular"
        )
        val gerente = getSystemService(DevicePolicyManager::class.java)
        val componente = ComponentName(this, AdminReceiver::class.java)
        try { if (gerente != null && gerente.isAdminActive(componente)) gerente.removeActiveAdmin(componente) } catch (_: Exception) {}
        stopService(Intent(this, LocalizacaoService::class.java))
        Prefs.removerTudo()
        finish()
    }
}

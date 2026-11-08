package br.com.protecaofamilia.dispositivo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.text.InputType
import android.util.TypedValue
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tela de apagamento remoto (comando "apagar_tudo", já confirmado 3x no painel).
 * Dá 15 segundos, em tela cheia, pra cancelar com o PIN antes de apagar de verdade —
 * uma rede de segurança caso o comando tenha sido disparado por engano.
 */
class ApagarActivity : Activity() {

    companion object {
        @Volatile private var ativo = false
        fun disparar(contexto: Context) {
            if (ativo) return
            contexto.startActivity(Intent(contexto, ApagarActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private var contagem: CountDownTimer? = null

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.iniciar(this)
        ativo = true

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        FamiliaRepo.enviarAlerta(
            Prefs.codigoFamilia, Prefs.idDispositivo,
            "apagar_iniciado", "Apagamento remoto iniciado — 15s para cancelar no próprio celular"
        )

        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, dp(16), 0, dp(16))
        }
        val campoPin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN para cancelar"
        }

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(56), dp(24), dp(24))
            setBackgroundColor(Color.parseColor("#C0152A"))
            addView(TextView(this@ApagarActivity).apply {
                text = "⚠️ Apagando todos os dados deste celular"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            })
            addView(status)
            addView(campoPin)
            addView(Button(this@ApagarActivity).apply {
                text = "CANCELAR (com o PIN)"
                setPadding(0, dp(16), 0, 0)
                setOnClickListener {
                    if (Prefs.verificarPin(campoPin.text.toString())) {
                        contagem?.cancel()
                        FamiliaRepo.enviarAlerta(
                            Prefs.codigoFamilia, Prefs.idDispositivo,
                            "apagar_cancelado", "Cancelado no próprio celular com o PIN"
                        )
                        ativo = false
                        finish()
                    } else {
                        status.text = "PIN incorreto."
                    }
                }
            })
        }
        setContentView(raiz)

        contagem = object : CountDownTimer(15_000, 1000) {
            override fun onTick(restante: Long) { status.text = "Restam ${restante / 1000 + 1} segundos…" }
            override fun onFinish() { apagar() }
        }.start()
    }

    private fun apagar() {
        val ok = DonoDispositivo.apagarTudo(this)
        if (!ok) {
            FamiliaRepo.enviarAlerta(
                Prefs.codigoFamilia, Prefs.idDispositivo,
                "apagar_falhou", "Não é \"Device Owner\": não é possível apagar remotamente"
            )
            ativo = false
            finish()
        }
        // se "ok", o próprio Android encerra o app em instantes pra apagar os dados.
    }

    override fun onDestroy() {
        super.onDestroy()
        contagem?.cancel()
        ativo = false
    }

    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        // de propósito: não deixa fechar com o botão de voltar sem o PIN
    }
}

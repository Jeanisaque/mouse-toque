package br.com.protecaofamilia.dispositivo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.util.TypedValue
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Toca um alarme alto em tela cheia, mesmo com o celular bloqueado, até digitar o PIN. */
class AlarmeActivity : Activity() {

    companion object {
        @Volatile private var ativo = false
        private var player: MediaPlayer? = null
        private var vibrador: Vibrator? = null

        fun disparar(contexto: Context) {
            if (ativo) return
            val intent = Intent(contexto, AlarmeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            contexto.startActivity(intent)
        }

        fun pararPorComando() = parar()

        private fun parar() {
            ativo = false
            player?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
            player = null
            vibrador?.cancel()
            FamiliaRepo.marcarAlarme(Prefs.codigoFamilia, Prefs.idDispositivo, false)
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.iniciar(this)
        ativo = true
        FamiliaRepo.marcarAlarme(Prefs.codigoFamilia, Prefs.idDispositivo, true)

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tocarSom()
        vibrar()

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(56), dp(24), dp(24))
            setBackgroundColor(Color.parseColor("#C0152A"))
        }
        raiz.addView(TextView(this).apply {
            text = "🔔 Alarme ativado"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        })
        raiz.addView(TextView(this).apply {
            text = "Digite o PIN para desligar o alarme."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, dp(12), 0, dp(24))
        })
        val campoPin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        raiz.addView(campoPin)
        val erro = TextView(this).apply {
            setTextColor(Color.YELLOW)
            setPadding(0, dp(8), 0, 0)
        }
        raiz.addView(erro)
        raiz.addView(Button(this).apply {
            text = "DESLIGAR ALARME"
            setPadding(0, dp(24), 0, 0)
            setOnClickListener {
                if (Prefs.verificarPin(campoPin.text.toString())) {
                    parar()
                    finish()
                } else {
                    erro.text = "PIN incorreto."
                }
            }
        })
        setContentView(raiz)
    }

    private fun tocarSom() {
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM) ?: return
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                setDataSource(this@AlarmeActivity, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {}
    }

    private fun vibrar() {
        vibrador = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        val padrao = longArrayOf(0, 800, 400)
        if (Build.VERSION.SDK_INT >= 26) {
            vibrador?.vibrate(VibrationEffect.createWaveform(padrao, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrador?.vibrate(padrao, 0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ativo) parar()
    }

    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        // de propósito: não deixa fechar com o botão de voltar sem o PIN
    }
}

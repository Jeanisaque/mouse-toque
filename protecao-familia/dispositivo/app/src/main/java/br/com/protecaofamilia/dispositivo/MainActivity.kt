package br.com.protecaofamilia.dispositivo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var raiz: LinearLayout
    private val componenteAdmin by lazy { ComponentName(this, AdminReceiver::class.java) }

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
            setColor(Color.parseColor(if (principal) "#4F8CFF" else "#1F232C"))
            cornerRadius = dp(14).toFloat()
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
        window.navigationBarColor = Color.parseColor("#0F1115")
        desenhar()
    }

    override fun onResume() {
        super.onResume()
        desenhar()
    }

    private fun desenhar() {
        raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(24))
        }

        val cabecalho = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cabecalho.addView(texto(24f, "#E9EBF1", true).apply {
            text = "Proteção Família"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (Prefs.protegido) {
            cabecalho.addView(Button(this).apply {
                text = "⚙"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(Color.parseColor("#8FB4FF"))
                background = null
                setOnClickListener { abrirConfiguracoesComPin() }
            })
        }
        raiz.addView(cabecalho)

        when {
            Prefs.codigoFamilia.isBlank() -> telaVincular()
            !Prefs.temPin -> telaDefinirPin()
            !temTodasPermissoes() -> telaPermissoes()
            else -> telaStatus()
        }

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#0F1115"))
            addView(raiz)
        })
    }

    // ------------------------------------------------------------ vincular à família

    private fun telaVincular() {
        raiz.addView(texto(15f, "#8B93A4").apply {
            text = "Cole abaixo o código da família que apareceu no app do painel (no celular dos pais)."
            setPadding(0, dp(16), 0, dp(16))
        })
        val campoCodigo = EditText(this).apply { hint = "Código da família (ex.: ABCD-EFGH-...)" }
        raiz.addView(campoCodigo)
        val campoNome = EditText(this).apply { hint = "Nome deste celular (ex.: Celular do João)" }
        raiz.addView(campoNome)
        val aviso = texto(14f, "#FF8A80").apply { setPadding(0, dp(8), 0, 0) }
        raiz.addView(aviso)
        raiz.addView(botao("Vincular") {
            val codigo = campoCodigo.text.toString().uppercase().filter { it.isLetterOrDigit() || it == '-' }
            val nome = campoNome.text.toString().trim()
            if (codigo.isBlank() || nome.isBlank()) { aviso.text = "Preencha o código e o nome."; return@botao }
            aviso.text = "Vinculando…"
            FamiliaRepo.vincular(codigo, nome, Prefs.idDispositivo) { ok ->
                runOnUiThread {
                    if (ok) {
                        Prefs.codigoFamilia = codigo
                        Prefs.nomeDispositivo = nome
                        desenhar()
                    } else {
                        aviso.text = "Não consegui vincular. Confira a internet e o código."
                    }
                }
            }
        })
    }

    // ------------------------------------------------------------ definir pin

    private fun telaDefinirPin() {
        raiz.addView(texto(15f, "#8B93A4").apply {
            text = "Crie um PIN de 4 a 6 números. Ele será pedido para desligar o alarme, mudar configurações ou remover a proteção."
            setPadding(0, dp(16), 0, dp(16))
        })
        val campoPin = EditText(this).apply {
            hint = "Novo PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val campoPin2 = EditText(this).apply {
            hint = "Confirme o PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        raiz.addView(campoPin); raiz.addView(campoPin2)
        val aviso = texto(14f, "#FF8A80").apply { setPadding(0, dp(8), 0, 0) }
        raiz.addView(aviso)
        raiz.addView(botao("Salvar PIN") {
            val p1 = campoPin.text.toString(); val p2 = campoPin2.text.toString()
            if (p1.length < 4 || p1.length > 6) { aviso.text = "O PIN precisa ter de 4 a 6 números."; return@botao }
            if (p1 != p2) { aviso.text = "Os PINs não são iguais."; return@botao }
            Prefs.definirPin(p1)
            desenhar()
        })
    }

    // ------------------------------------------------------------ permissões

    private fun temTodasPermissoes(): Boolean {
        val local = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fundo = Build.VERSION.SDK_INT < 29 ||
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        return local && fundo
    }

    private fun telaPermissoes() {
        raiz.addView(texto(15f, "#8B93A4").apply {
            text = "Pra funcionar mesmo com a tela apagada, o app precisa de algumas permissões."
            setPadding(0, dp(16), 0, dp(16))
        })
        raiz.addView(botao("Permitir localização") { pedirPermissoes() })
    }

    private fun pedirPermissoes() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            return
        }
        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            AlertDialog.Builder(this)
                .setTitle("Localização sempre")
                .setMessage("Na próxima tela, escolha \"Permitir todo o tempo\" para a proteção funcionar com o app em segundo plano.")
                .setPositiveButton("Entendi") { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
                }.show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            return
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 4)
            return
        }
        pedirIgnorarOtimizacaoBateria()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pedirPermissoes()
    }

    private fun pedirIgnorarOtimizacaoBateria() {
        val gerente = getSystemService(PowerManager::class.java)
        if (gerente != null && !gerente.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
        ativarAdministrador()
    }

    private fun ativarAdministrador() {
        val gerente = getSystemService(DevicePolicyManager::class.java)
        if (gerente != null && !gerente.isAdminActive(componenteAdmin)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componenteAdmin)
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Necessário para travar a tela remotamente, caso o celular seja perdido ou roubado."
                )
            try { startActivity(intent) } catch (_: Exception) {}
        }
        DonoDispositivo.aplicarProtecaoMaxima(this)
        IniciarServico.agora(this)
    }

    // ------------------------------------------------------------ status

    private fun telaStatus() {
        val maxima = DonoDispositivo.ehDono(this)
        raiz.addView(texto(15f, "#4ADE80", true).apply {
            text = if (maxima) "✓✓ Protegido (proteção máxima)" else "✓ Protegido"
            setPadding(0, dp(20), 0, dp(6))
        })
        raiz.addView(texto(14f, "#8B93A4").apply {
            text = "Celular: ${Prefs.nomeDispositivo}\nFamília: ${Prefs.codigoFamilia}"
        })
        if (!maxima) {
            raiz.addView(texto(13f, "#8FB4FF").apply {
                text = "Dá para reforçar a proteção (impedir formatar e desinstalar com mais força). Veja ⚙ → \"Proteção máxima\"."
                setPadding(0, dp(10), 0, 0)
            })
        }
        raiz.addView(texto(13f, "#6B7280").apply {
            text = "Este app manda a localização para quem cuida deste celular, mesmo com a tela apagada, " +
                "e avisa se o celular for desligado, tiver a bateria crítica, ou trocarem o chip."
            setPadding(0, dp(16), 0, 0)
        })
        IniciarServico.agora(this)
    }

    // ------------------------------------------------------------ configurações (pedem pin)

    private fun abrirConfiguracoesComPin() {
        val campo = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("Digite o PIN")
            .setView(campo)
            .setPositiveButton("Entrar") { _, _ ->
                if (Prefs.verificarPin(campo.text.toString())) {
                    startActivity(Intent(this, ConfiguracoesActivity::class.java))
                } else {
                    AlertDialog.Builder(this).setMessage("PIN incorreto.").setPositiveButton("OK", null).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

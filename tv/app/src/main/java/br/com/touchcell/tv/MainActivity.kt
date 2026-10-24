package br.com.touchcell.tv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

/** Tela da TV: mostra o QR code para o celular, ou como ativar o serviço. */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var titulo: TextView
    private lateinit var texto: TextView
    private lateinit var qr: ImageView
    private lateinit var endereco: TextView
    private lateinit var estado: TextView
    private lateinit var botao: Button
    private lateinit var aviso: TextView
    private lateinit var completo: TextView
    private lateinit var botaoCompleto: Button
    private lateinit var botaoPermitir: Button
    private var urlAtual = ""
    @Volatile private var atualizando = false

    private val atualizar = object : Runnable {
        override fun run() { mostrar(); handler.postDelayed(this, 2000) }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun rotulo(tam: Float, cor: Int, negrito: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, tam)
        setTextColor(cor)
        gravity = Gravity.CENTER
        if (negrito) typeface = Typeface.DEFAULT_BOLD
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F1115"))
            setPadding(dp(48), dp(32), dp(48), dp(24))
        }
        titulo = rotulo(34f, Color.WHITE, true).apply { text = "Touch Cell" }
        texto = rotulo(18f, Color.parseColor("#A9B0BF")).apply { setPadding(0, dp(8), 0, dp(16)) }
        qr = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(180), dp(180))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat() }
        }
        endereco = rotulo(18f, Color.parseColor("#E9EBF1")).apply { setPadding(0, dp(14), 0, 0) }
        estado = rotulo(16f, Color.parseColor("#F5B041")).apply { setPadding(0, dp(8), 0, 0) }
        botao = Button(this).apply {
            text = "Abrir configurações de Acessibilidade"
            setOnClickListener { abrirAcessibilidade() }
        }
        completo = rotulo(15f, Color.parseColor("#A9B0BF")).apply { setPadding(0, dp(12), 0, dp(4)) }
        botaoCompleto = Button(this).apply {
            text = "Ativar controle completo"
            setOnClickListener { ativarCompleto() }
        }
        aviso = rotulo(16f, Color.parseColor("#8FB4FF")).apply { setPadding(0, dp(10), 0, 0); visibility = View.GONE }
        botaoPermitir = Button(this).apply {
            text = "Permitir atualizações automáticas"
            visibility = View.GONE
            setOnClickListener { abrirPermissaoInstalar() }
        }
        val creditos = rotulo(14f, Color.parseColor("#8B93A4")).apply {
            text = "Criado por Isaque Andrade e Joel Andrade"
            setPadding(0, dp(18), 0, 0)
        }
        raiz.addView(titulo); raiz.addView(texto); raiz.addView(qr)
        raiz.addView(endereco); raiz.addView(estado); raiz.addView(botao)
        raiz.addView(completo); raiz.addView(botaoCompleto)
        raiz.addView(aviso); raiz.addView(botaoPermitir); raiz.addView(creditos)
        val rolagem = android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#0F1115"))
            addView(raiz)
        }
        setContentView(rolagem)
    }

    override fun onResume() {
        super.onResume()
        handler.post(atualizar)
        procurarAtualizacao()
    }

    // ---------------------------------------------------------------- atualização

    private fun podeInstalar() = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun abrirPermissaoInstalar() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun procurarAtualizacao() {
        if (atualizando) return
        Thread {
            if (!Atualizador.verificar(this)) return@Thread
            runOnUiThread {
                aviso.visibility = View.VISIBLE
                if (!podeInstalar()) {
                    aviso.text = "Versão nova disponível (${Atualizador.nomePublicado}). " +
                        "Permita uma vez que o Touch Cell instale atualizações:"
                    botaoPermitir.visibility = View.VISIBLE
                    botaoPermitir.requestFocus()
                } else {
                    botaoPermitir.visibility = View.GONE
                    instalarAtualizacao()
                }
            }
        }.start()
    }

    private fun instalarAtualizacao() {
        if (atualizando) return
        atualizando = true
        aviso.text = "Baixando a versão ${Atualizador.nomePublicado}…"
        Thread {
            val ok = try {
                Atualizador.baixarEInstalar(this) { pct -> runOnUiThread { aviso.text = "Baixando a versão ${Atualizador.nomePublicado}… $pct%" } }
            } catch (e: Exception) { false }
            runOnUiThread {
                aviso.text = if (ok) "Confirme a instalação na tela da TV (clique em Atualizar)."
                    else "Não consegui baixar a atualização. Tento de novo da próxima vez."
                atualizando = false
            }
        }.start()
    }
    override fun onPause() { handler.removeCallbacks(atualizar); super.onPause() }

    private fun ativarCompleto() {
        val a = TouchService.instancia?.adb ?: return
        a.conectar { runOnUiThread { mostrar() } }
        // se a depuração estiver desligada, abre as opções de desenvolvedor
        handler.postDelayed({
            if (!a.conectado) {
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                catch (e: Exception) { try { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) } catch (_: Exception) {} }
            }
        }, 4000)
    }

    private fun mostrarCompleto(servico: TouchService) {
        val a = servico.adb
        if (a != null && a.conectado) {
            completo.text = "✓ Controle completo ativo: setas e OK funcionam dentro dos apps" +
                (if (a.rapido) "" else " (modo lento)") +
                (a.ultimoErro?.takeIf { it.startsWith("Ajudante") }?.let { "\n$it" } ?: "")
            completo.setTextColor(Color.parseColor("#2ECC71"))
            botaoCompleto.visibility = View.GONE
        } else {
            completo.text = "Controle completo (para as setas funcionarem dentro dos apps):\n" +
                "1. Configurações › Preferências do dispositivo › Sobre › clique 7 vezes em \"Build\"\n" +
                "2. Volte, entre em Opções do desenvolvedor e ative \"Depuração USB\"\n" +
                "3. Clique no botão abaixo e, na pergunta da TV, marque \"Sempre permitir\" e OK"
            a?.ultimoErro?.let { completo.text = completo.text.toString() + "\n\nÚltima tentativa: " + it }
            completo.setTextColor(Color.parseColor("#A9B0BF"))
            botaoCompleto.visibility = View.VISIBLE
        }
    }

    private fun abrirAcessibilidade() {
        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun mostrar() {
        val servico = TouchService.instancia
        if (servico == null) {
            texto.text = "Falta só um passo: ative o Touch Cell na Acessibilidade da TV.\n" +
                "Configurações › Preferências do dispositivo › Acessibilidade › Touch Cell › Ativar"
            qr.visibility = View.GONE
            endereco.visibility = View.GONE
            estado.visibility = View.GONE
            botao.visibility = View.VISIBLE
            completo.visibility = View.GONE
            botaoCompleto.visibility = View.GONE
            if (!botao.isFocused) botao.requestFocus()
            return
        }
        botao.visibility = View.GONE
        completo.visibility = View.VISIBLE
        mostrarCompleto(servico)
        qr.visibility = View.VISIBLE
        endereco.visibility = View.VISIBLE
        estado.visibility = View.VISIBLE
        val ip = ipLocal()
        val porta = servico.servidor?.porta ?: Servidor.PORTA_PADRAO
        val codigo = Servidor.codigo(this)
        texto.text = "Aponte a câmera do celular para o código (mesmo Wi-Fi da TV)"
        if (ip == null) {
            endereco.text = "A TV não está conectada a uma rede"
            qr.visibility = View.GONE
        } else {
            val url = "http://$ip:$porta/?k=$codigo"
            if (url != urlAtual) { qr.setImageBitmap(gerarQr(url)); urlAtual = url }
            endereco.text = "$ip:$porta   ·   código $codigo"
        }
        if (servico.conectados > 0) {
            estado.text = "Celular conectado!"
            estado.setTextColor(Color.parseColor("#2ECC71"))
        } else {
            estado.text = "Esperando o celular…"
            estado.setTextColor(Color.parseColor("#F5B041"))
        }
    }

    private fun ipLocal(): String? {
        try {
            val lista = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            val candidatos = lista.filter { it.isUp && !it.isLoopback }
                .flatMap { i -> i.inetAddresses.toList().map { i.name to it } }
                .filter { it.second is Inet4Address && it.second.isSiteLocalAddress }
            val preferido = candidatos.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("eth") }
                ?: candidatos.firstOrNull()
            return preferido?.second?.hostAddress
        } catch (e: Exception) { return null }
    }

    private fun gerarQr(texto: String): Bitmap {
        val tam = 400
        val m = QRCodeWriter().encode(texto, BarcodeFormat.QR_CODE, tam, tam, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(tam * tam)
        for (yy in 0 until tam) for (xx in 0 until tam) px[yy * tam + xx] = if (m.get(xx, yy)) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, tam, tam, Bitmap.Config.RGB_565)
    }
}

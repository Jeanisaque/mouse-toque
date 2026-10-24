package br.com.touchcell.celular

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject

/**
 * Tela de controle: abre a mesma tela do Touch Cell que vem do PC/TV,
 * e acrescenta o que só um app consegue: microfone de verdade e os
 * botões de volume do celular controlando o PC/TV.
 */
class ControleActivity : Activity() {

    private lateinit var web: WebView
    private val pedidoVoz = 7
    private val pedidoFoto = 8
    private var esperandoFoto: ValueCallback<Array<Uri>>? = null

    inner class Ponte {
        @JavascriptInterface
        fun ouvir() { runOnUiThread { iniciarVoz() } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#0F1115")
        window.navigationBarColor = Color.parseColor("#0F1115")

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0F1115"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportZoom(false)
            isHapticFeedbackEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addJavascriptInterface(Ponte(), "TouchCellApp")
            // abre a galeria quando a página pede uma foto (foto de fundo do touchpad)
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(v: WebView, callback: ValueCallback<Array<Uri>>,
                                               params: FileChooserParams): Boolean {
                    esperandoFoto?.onReceiveValue(null)
                    esperandoFoto = callback
                    val galeria = Intent(Intent.ACTION_GET_CONTENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/*")
                    return try {
                        @Suppress("DEPRECATION")
                        startActivityForResult(Intent.createChooser(galeria, "Escolha uma foto"), pedidoFoto)
                        true
                    } catch (e: Exception) {
                        esperandoFoto = null
                        false
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(v: WebView, req: WebResourceRequest, erro: WebResourceError) {
                    if (req.isForMainFrame) {
                        Toast.makeText(this@ControleActivity,
                            "Não consegui conectar. O Touch Cell está aberto no aparelho?", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        }
        setContentView(web)
        web.loadUrl(intent.getStringExtra("url") ?: run { finish(); return })
        avisarAtualizacao()
    }

    /** Se houver versão nova do app, pergunta aqui mesmo (sem precisar voltar à lista). */
    private fun avisarAtualizacao() {
        Thread {
            if (!Atualizador.verificar(this)) return@Thread
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                android.app.AlertDialog.Builder(this)
                    .setTitle("Versão nova do Touch Cell")
                    .setMessage("A versão ${Atualizador.nomePublicado} está disponível. Atualizar agora?")
                    .setPositiveButton("Atualizar") { _, _ -> atualizar() }
                    .setNegativeButton("Depois", null)
                    .show()
            }
        }.start()
    }

    private fun atualizar() {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pediuPermissao = true
            Toast.makeText(this, "Permita que o Touch Cell instale atualizações e volte aqui", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
            return
        }
        Toast.makeText(this, "Baixando a atualização…", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = try { Atualizador.baixarEInstalar(this) {} } catch (e: Exception) { false }
            if (!ok) runOnUiThread { Toast.makeText(this, "Não consegui baixar agora", Toast.LENGTH_LONG).show() }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // voltou da tela de permissão: tenta de novo
        if (android.os.Build.VERSION.SDK_INT >= 26 && Atualizador.temNova(this) && packageManager.canRequestPackageInstalls()
            && pediuPermissao) { pediuPermissao = false; atualizar() }
    }
    private var pediuPermissao = false

    private fun iniciarVoz() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale o que você quer procurar")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, pedidoVoz)
        } catch (e: Exception) {
            Toast.makeText(this, "O reconhecimento de voz não está disponível neste celular", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pedidoFoto) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            esperandoFoto?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
            esperandoFoto = null
            return
        }
        if (requestCode != pedidoVoz || resultCode != RESULT_OK) return
        val falado = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        web.evaluateJavascript("window.mtTextoFalado && window.mtTextoFalado(${JSONObject.quote(falado)})", null)
    }

    /** Botões de volume do celular controlam o volume do PC/TV. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val tecla = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "volup"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "voldown"
            else -> null
        }
        if (tecla != null) {
            web.evaluateJavascript("window.mtTecla && window.mtTecla('$tecla')", null)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}

package br.com.criadorsites

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Ponte(), "Android")
        web.loadUrl("file:///android_asset/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Deixa o editor tratar o voltar (fechar prévia/menu); se ele não tratar, sai.
                web.evaluateJavascript("window.voltar ? window.voltar() : false") { r ->
                    if (r != "true") { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
    }

    private fun nomeSeguro(nome: String): String {
        val limpo = nome.replace(Regex("[^A-Za-z0-9_-]"), "-").trim('-').ifEmpty { "meu-site" }
        return "$limpo.html"
    }

    private fun aviso(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    inner class Ponte {
        /** Salva o site na pasta Downloads. */
        @JavascriptInterface
        fun salvar(nome: String, html: String) {
            try {
                val arquivo = nomeSeguro(nome)
                if (Build.VERSION.SDK_INT >= 29) {
                    val valores = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, arquivo)
                        put(MediaStore.Downloads.MIME_TYPE, "text/html")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
                        ?: throw IllegalStateException("sem acesso a Downloads")
                    contentResolver.openOutputStream(uri)!!.use { it.write(html.toByteArray()) }
                    aviso("Salvo em Downloads: $arquivo")
                } else {
                    val pasta = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
                    File(pasta, arquivo).writeText(html)
                    aviso("Salvo em ${pasta.absolutePath}/$arquivo")
                }
            } catch (e: Exception) {
                aviso("Não consegui salvar: ${e.message}")
            }
        }

        /** Abre o menu de compartilhar com o arquivo .html. */
        @JavascriptInterface
        fun compartilhar(nome: String, html: String) {
            try {
                val pasta = File(cacheDir, "sites").apply { mkdirs() }
                val arq = File(pasta, nomeSeguro(nome)).apply { writeText(html) }
                val uri = FileProvider.getUriForFile(this@MainActivity, "br.com.criadorsites.arquivos", arq)
                val envio = Intent(Intent.ACTION_SEND).apply {
                    type = "text/html"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(envio, "Enviar site"))
            } catch (e: Exception) {
                aviso("Não consegui compartilhar: ${e.message}")
            }
        }

        /** Abre o site gerado no navegador do celular. */
        @JavascriptInterface
        fun abrirNoNavegador(nome: String, html: String) {
            try {
                val pasta = File(cacheDir, "sites").apply { mkdirs() }
                val arq = File(pasta, nomeSeguro(nome)).apply { writeText(html) }
                val uri = FileProvider.getUriForFile(this@MainActivity, "br.com.criadorsites.arquivos", arq)
                val ver = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "text/html")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(ver, "Abrir com"))
            } catch (e: Exception) {
                aviso("Não consegui abrir: ${e.message}")
            }
        }
    }
}

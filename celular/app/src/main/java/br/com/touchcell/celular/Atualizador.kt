package br.com.touchcell.celular

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização automática do app do celular: confere a versão publicada no GitHub,
 * baixa o APK novo e pede para o Android instalar (o celular mostra uma tela de
 * confirmação, que é obrigatória para apps de fora da loja).
 */
object Atualizador {
    private const val BASE = "https://github.com/Jeanisaque/mouse-toque/releases/download/celular/"
    private const val URL_INFO = BASE + "versao-celular.json"
    private const val URL_APK = BASE + BuildConfig.APK

    @Volatile var versaoPublicada: Long = 0
        private set
    @Volatile var nomePublicado: String = ""
        private set

    fun versaoInstalada(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    fun temNova(ctx: Context) = versaoPublicada > versaoInstalada(ctx)

    private fun abrir(url: String): HttpURLConnection {
        var atual = url
        repeat(5) {
            val c = URL(atual).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "TouchCellCelular")
            val cod = c.responseCode
            if (cod in 300..399) {
                atual = c.getHeaderField("Location") ?: throw IllegalStateException("redirecionamento sem destino")
                c.disconnect()
            } else return c
        }
        throw IllegalStateException("redirecionamentos demais")
    }

    /** Roda fora da thread principal. Devolve true se houver versão nova. */
    fun verificar(ctx: Context): Boolean {
        return try {
            val c = abrir(URL_INFO + "?t=" + System.currentTimeMillis())
            if (c.responseCode != 200) return false
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            versaoPublicada = json.optLong("versionCode", 0)
            nomePublicado = json.optString("versionName", "")
            temNova(ctx)
        } catch (e: Exception) {
            false
        }
    }

    /** Roda fora da thread principal. Baixa e entrega ao instalador do Android. */
    fun baixarEInstalar(ctx: Context, progresso: (Int) -> Unit): Boolean {
        val arquivo = File(ctx.cacheDir, "atualizacao.apk")
        val c = abrir(URL_APK)
        if (c.responseCode != 200) return false
        val total = c.contentLengthLong
        c.inputStream.use { ent ->
            arquivo.outputStream().use { sai ->
                val buf = ByteArray(64 * 1024)
                var lido = 0L
                while (true) {
                    val n = ent.read(buf); if (n < 0) break
                    sai.write(buf, 0, n); lido += n
                    if (total > 0) progresso((lido * 100 / total).toInt())
                }
            }
        }
        val instalador = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = instalador.createSession(params)
        instalador.openSession(id).use { sessao ->
            arquivo.inputStream().use { ent ->
                sessao.openWrite("touchcell.apk", 0, arquivo.length()).use { sai ->
                    ent.copyTo(sai); sessao.fsync(sai)
                }
            }
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(ctx, id,
                Intent(ctx, InstaladorReceiver::class.java), flags or PendingIntent.FLAG_UPDATE_CURRENT)
            sessao.commit(pi.intentSender)
        }
        return true
    }
}

/** Recebe a resposta do instalador e abre a tela de confirmação. */
class InstaladorReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmar = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirmar?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(it)
            }
        }
    }
}

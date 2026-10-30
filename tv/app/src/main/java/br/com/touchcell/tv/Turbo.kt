package br.com.touchcell.tv

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Turbo TV": deixa a TV mais rápida pelo Controle completo (ADB).
 * - animações mais rápidas ou desligadas
 * - limpar memória (fecha apps em segundo plano e limpa o cache)
 * - desativar apps que a pessoa não usa (dá pra reativar a qualquer hora)
 */
class Turbo(private val ctx: Context, private val adb: () -> AdbControle?) {

    private val prefs = ctx.getSharedPreferences("turbo", Context.MODE_PRIVATE)

    /** Apps que nunca aparecem na lista (a TV precisa deles). */
    private val essenciais = setOf(
        ctx.packageName,
        "com.android.tv.settings", "com.android.settings",
        "com.google.android.tvlauncher", "com.google.android.leanbacklauncher",
        "com.google.android.apps.tv.launcherx", "com.android.vending",
        "com.google.android.gms", "com.google.android.tv.remote.service",
        "com.google.android.katniss"
    )

    private val ligado get() = adb()?.conectado == true

    fun memoria(): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        fun gb(b: Long) = String.format("%.1f", b / 1_073_741_824.0).replace('.', ',')
        return "${gb(mi.availMem)} GB livres de ${gb(mi.totalMem)} GB"
    }

    private fun desativados(): MutableSet<String> = HashSet(prefs.getStringSet("desativados", emptySet())!!)

    private fun apps(): JSONArray {
        val pm = ctx.packageManager
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS
        val achados = LinkedHashMap<String, String>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER))
            for (r in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), flags)) {
                val p = r.activityInfo.packageName
                if (p !in essenciais && p !in achados) achados[p] = r.loadLabel(pm).toString()
            }
        for (p in desativados()) if (p !in achados) achados[p] = try {
            pm.getApplicationInfo(p, flags).loadLabel(pm).toString()
        } catch (e: Exception) { p }
        val arr = JSONArray()
        achados.entries.sortedBy { it.value.lowercase() }.forEach { (p, n) ->
            val estado = try { pm.getApplicationEnabledSetting(p) } catch (e: Exception) { 0 }
            val ativo = estado != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
                estado != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            arr.put(JSONObject().put("p", p).put("n", n).put("on", ativo))
        }
        return arr
    }

    /** Monta o estado para a tela de Ajustes do celular. */
    fun estado(responder: (JSONObject) -> Unit) {
        val base = JSONObject().put("t", "turbo").put("adb", ligado).put("mem", memoria())
        val a = adb()
        if (a == null || !a.conectado) { responder(base.put("apps", apps())); return }
        a.executar("settings get global animator_duration_scale; settings get secure screensaver_enabled") { s ->
            val linhas = s?.lines()?.map { it.trim() } ?: emptyList()
            val v = linhas.getOrNull(0)?.toFloatOrNull() ?: 1f
            val protecao = linhas.getOrNull(1) != "0"      // "null" ou "1" = ligada
            responder(base.put("anim", v.toDouble()).put("protecao", protecao).put("apps", apps()))
        }
    }

    /**
     * Proteção de tela e Modo ambiente (o "papel de parede" que aparece quando pausa um vídeo).
     * Além da configuração, liga/desliga os apps que mostram as fotos (Backdrop, Modo ambiente,
     * proteção de tela da marca), achados pelo nome do pacote.
     */
    fun protecao(ligada: Boolean, depois: () -> Unit) {
        val v = if (ligada) 1 else 0
        val acao = if (ligada) "pm enable \$p" else "am force-stop \$p; pm disable-user --user 0 \$p"
        adb()?.executar(
            "settings put secure screensaver_enabled $v; " +
            "settings put secure screensaver_activate_on_sleep $v; " +
            "settings put secure screensaver_activate_on_dock $v; " +
            "for p in \$(pm list packages | grep -iE 'backdrop|screensaver|dreamx|ambient' | cut -d: -f2); do $acao; done"
        ) { depois() } ?: depois()
    }

    fun animacoes(escala: Float, depois: () -> Unit) {
        val v = escala.coerceIn(0f, 1f).toString()
        adb()?.executar(
            "settings put global window_animation_scale $v; " +
            "settings put global transition_animation_scale $v; " +
            "settings put global animator_duration_scale $v"
        ) { depois() } ?: depois()
    }

    fun limpar(depois: (String) -> Unit) {
        val antes = memoria()
        val a = adb()
        if (a == null || !a.conectado) { depois("Ative o Controle completo na TV"); return }
        a.executar("am kill-all; pm trim-caches 999G") {
            Thread.sleep(800)
            depois("Memória limpa · antes: $antes · agora: ${memoria()}")
        }
    }

    fun app(pacote: String, ativar: Boolean, depois: () -> Unit) {
        if (pacote in essenciais || !pacote.matches(Regex("[A-Za-z0-9_.]+"))) { depois(); return }
        val cmd = if (ativar) "pm enable $pacote" else "am force-stop $pacote; pm disable-user --user 0 $pacote"
        adb()?.executar(cmd) {
            val d = desativados()
            if (ativar) d.remove(pacote) else d.add(pacote)
            prefs.edit().putStringSet("desativados", d).apply()
            depois()
        } ?: depois()
    }
}

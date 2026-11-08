package br.com.protecaofamilia.dispositivo

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager

/**
 * Restrições extras, só possíveis quando o app é "Device Owner" (ativado via ADB,
 * com o celular zerado, antes de configurar qualquer conta — veja o README).
 *
 * Isso NUNCA formata nada: é só um nível mais alto de permissão que o próprio
 * Android dá a um app quando ele é configurado como "dono do dispositivo" logo
 * na primeira vez que o celular é ligado.
 */
object DonoDispositivo {

    fun ehDono(contexto: Context): Boolean {
        val dpm = contexto.getSystemService(DevicePolicyManager::class.java) ?: return false
        return dpm.isDeviceOwnerApp(contexto.packageName)
    }

    fun aplicarProtecaoMaxima(contexto: Context) {
        if (!ehDono(contexto)) return
        val dpm = contexto.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = ComponentName(contexto, AdminReceiver::class.java)
        try {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            dpm.setUninstallBlocked(admin, contexto.packageName, true)
        } catch (_: SecurityException) {}
    }

    fun removerProtecaoMaxima(contexto: Context) {
        if (!ehDono(contexto)) return
        val dpm = contexto.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = ComponentName(contexto, AdminReceiver::class.java)
        try {
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            dpm.setUninstallBlocked(admin, contexto.packageName, false)
        } catch (_: SecurityException) {}
    }

    /** Tenta apagar os dados do celular. Só funciona de verdade se for Device Owner. */
    fun apagarTudo(contexto: Context): Boolean {
        val dpm = contexto.getSystemService(DevicePolicyManager::class.java) ?: return false
        if (!dpm.isDeviceOwnerApp(contexto.packageName)) return false
        return try {
            dpm.wipeData(0)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}

package br.com.protecaofamilia.dispositivo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Detecção de troca de chip (SIM): melhor esforço.
 * A partir do Android 10 o sistema restringe o acesso ao número de série do chip,
 * então usamos o nome da operadora como pista. Pode não detectar em todos os aparelhos.
 */
object SimMonitor {
    fun verificar(contexto: Context) {
        if (ContextCompat.checkSelfPermission(contexto, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val gerente = contexto.getSystemService(TelephonyManager::class.java) ?: return
        val atual = try {
            @Suppress("DEPRECATION")
            gerente.simOperatorName + "|" + gerente.simSerialNumber
        } catch (_: SecurityException) {
            return
        }
        val salvo = Prefs.simSalvo
        if (salvo.isNotBlank() && salvo != atual) {
            FamiliaRepo.enviarAlerta(
                Prefs.codigoFamilia, Prefs.idDispositivo,
                "chip_trocado", "O chip (SIM) deste celular foi trocado"
            )
        }
        Prefs.simSalvo = atual
    }
}

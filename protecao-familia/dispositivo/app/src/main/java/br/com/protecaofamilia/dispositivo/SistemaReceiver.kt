package br.com.protecaofamilia.dispositivo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Reage a três eventos do sistema:
 * - celular ligou: reinicia o serviço de proteção.
 * - celular vai desligar: manda a última posição conhecida, rápido, antes de perder o sinal.
 * - bateria crítica: mesma coisa (é um bom sinal de que o celular vai ficar inalcançável).
 *
 * "ACTION_SHUTDOWN" é melhor esforço: a maioria dos Android entrega esse aviso, mas
 * alguns fabricantes não entregam se o usuário forçar o desligamento mantendo o botão.
 */
class SistemaReceiver : BroadcastReceiver() {
    override fun onReceive(contexto: Context, intent: Intent) {
        Prefs.iniciar(contexto)
        if (!Prefs.protegido) return

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> IniciarServico.agora(contexto)
            Intent.ACTION_SHUTDOWN -> avisar(contexto, "desligando")
            Intent.ACTION_BATTERY_LOW -> avisar(contexto, "bateria_critica")
        }
    }

    private fun avisar(contexto: Context, motivo: String) {
        val servico = LocalizacaoService.instancia
        if (servico != null) {
            servico.enviarUltimaLocalizacaoUrgente(motivo)
        } else {
            FamiliaRepo.enviarAlerta(Prefs.codigoFamilia, Prefs.idDispositivo, motivo)
        }
    }
}

object IniciarServico {
    fun agora(contexto: Context) {
        if (!Prefs.protegido) return
        val temPermissao = ContextCompat.checkSelfPermission(contexto, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!temPermissao) return
        val intent = Intent(contexto, LocalizacaoService::class.java)
        if (Build.VERSION.SDK_INT >= 26) contexto.startForegroundService(intent) else contexto.startService(intent)
    }
}

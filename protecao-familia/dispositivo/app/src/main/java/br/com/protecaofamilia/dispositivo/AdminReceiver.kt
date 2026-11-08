package br.com.protecaofamilia.dispositivo

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/** Permite travar a tela remotamente e torna mais difícil desinstalar o app sem o PIN. */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onDisabled(contexto: Context, intent: Intent) {
        super.onDisabled(contexto, intent)
        Prefs.iniciar(contexto)
        if (Prefs.protegido) {
            FamiliaRepo.enviarAlerta(
                Prefs.codigoFamilia, Prefs.idDispositivo,
                "protecao_desativada", "A proteção deste celular foi desativada pelas configurações do Android"
            )
        }
    }

    override fun onDisableRequested(contexto: Context, intent: Intent): CharSequence {
        return "Desativar por aqui (sem usar o PIN do app) avisa quem cuida deste celular."
    }
}

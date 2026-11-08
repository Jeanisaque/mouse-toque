package br.com.protecaofamilia.dispositivo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.firestore.ListenerRegistration

/** Serviço em primeiro plano: manda localização e bateria periodicamente, e escuta comandos do painel. */
class LocalizacaoService : Service() {

    companion object {
        private const val CANAL = "protecao_familia"
        private const val NOTIFICACAO_ID = 1
        private const val INTERVALO_MS = 3 * 60 * 1000L // 3 minutos

        @Volatile var instancia: LocalizacaoService? = null
            private set
    }

    private lateinit var clienteLocalizacao: FusedLocationProviderClient
    private var escuta: ListenerRegistration? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(resultado: LocationResult) {
            resultado.lastLocation?.let { enviar(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instancia = this
        Prefs.iniciar(applicationContext)
        clienteLocalizacao = LocationServices.getFusedLocationProviderClient(this)

        criarCanal()
        iniciarPrimeiroPlano()
        iniciarAtualizacoesLocalizacao()
        iniciarEscutaComandos()
        SimMonitor.verificar(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "LOCALIZAR_AGORA") localizarAgora()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instancia = null
        escuta?.remove()
        try { clienteLocalizacao.removeLocationUpdates(callback) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------ notificação

    private fun criarCanal() {
        val gerente = getSystemService(NotificationManager::class.java) ?: return
        if (gerente.getNotificationChannel(CANAL) == null) {
            gerente.createNotificationChannel(
                NotificationChannel(CANAL, "Proteção Família", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }

    private fun iniciarPrimeiroPlano() {
        val abrirApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notificacao: Notification = NotificationCompat.Builder(this, CANAL)
            .setContentTitle("Proteção Família")
            .setContentText("Protegendo este celular")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(abrirApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICACAO_ID, notificacao, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICACAO_ID, notificacao)
        }
    }

    // ------------------------------------------------------------ localização

    private fun iniciarAtualizacoesLocalizacao() {
        val pedido = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, INTERVALO_MS)
            .setMinUpdateIntervalMillis(60_000L)
            .build()
        try {
            clienteLocalizacao.requestLocationUpdates(pedido, callback, mainLooper)
        } catch (_: SecurityException) {
            // sem permissão ainda; a MainActivity cuida de pedir
        }
    }

    fun localizarAgora() {
        try {
            clienteLocalizacao.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc -> if (loc != null) enviar(loc) }
        } catch (_: SecurityException) {}
    }

    private fun enviar(loc: Location) {
        FamiliaRepo.enviarLocalizacao(
            Prefs.codigoFamilia, Prefs.idDispositivo,
            loc.latitude, loc.longitude, loc.accuracy, nivelBateria()
        )
    }

    private fun nivelBateria(): Int {
        val gerente = getSystemService(android.os.BatteryManager::class.java)
        return gerente?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    /** Chamado pelo SistemaReceiver quando o celular está desligando ou com bateria crítica. */
    fun enviarUltimaLocalizacaoUrgente(motivo: String) {
        FamiliaRepo.enviarAlerta(Prefs.codigoFamilia, Prefs.idDispositivo, motivo)
        try {
            clienteLocalizacao.lastLocation.addOnSuccessListener { loc -> if (loc != null) enviar(loc) }
        } catch (_: SecurityException) {}
    }

    // ------------------------------------------------------------ comandos do painel

    private fun iniciarEscutaComandos() {
        escuta = FamiliaRepo.escutarComandos(Prefs.codigoFamilia, Prefs.idDispositivo) { idComando, tipo ->
            when (tipo) {
                "alarme" -> AlarmeActivity.disparar(this)
                "parar_alarme" -> AlarmeActivity.pararPorComando()
                "travar" -> travarTela()
                "localizar" -> localizarAgora()
            }
            FamiliaRepo.marcarComandoAtendido(Prefs.codigoFamilia, Prefs.idDispositivo, idComando)
        }
    }

    private fun travarTela() {
        try {
            val gerente = getSystemService(DevicePolicyManager::class.java)
            gerente?.lockNow() ?: throw SecurityException("sem administrador")
        } catch (_: SecurityException) {
            FamiliaRepo.enviarAlerta(
                Prefs.codigoFamilia, Prefs.idDispositivo,
                "travar_falhou", "Administrador do dispositivo não está ativo"
            )
        }
    }
}

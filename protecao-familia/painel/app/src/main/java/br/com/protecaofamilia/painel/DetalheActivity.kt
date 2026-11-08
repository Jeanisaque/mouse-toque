package br.com.protecaofamilia.painel

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.firestore.ListenerRegistration
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class DetalheActivity : Activity() {

    private lateinit var mapa: MapView
    private var marcador: Marker? = null
    private var ultimoPonto: GeoPoint? = null
    private var escutaDispositivo: ListenerRegistration? = null
    private var escutaHistorico: ListenerRegistration? = null
    private var escutaAlertas: ListenerRegistration? = null
    private lateinit var idDispositivo: String

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.iniciar(this)
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().osmdroidTileCache = cacheDir

        idDispositivo = intent.getStringExtra("id") ?: ""
        val nome = intent.getStringExtra("nome") ?: ""

        val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0F1115")) }

        raiz.addView(TextView(this).apply {
            text = nome
            setTextColor(Color.parseColor("#E9EBF1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(dp(20), dp(24), dp(20), dp(8))
        })

        mapa = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360))
        }
        raiz.addView(mapa)

        val botoes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(20)) }
        botoes.addView(botaoAcao("🔔 Tocar alarme") { FamiliaRepo.enviarComando(Prefs.codigoFamilia, idDispositivo, "alarme") })
        botoes.addView(botaoAcao("🔕 Parar alarme") { FamiliaRepo.enviarComando(Prefs.codigoFamilia, idDispositivo, "parar_alarme") })
        botoes.addView(botaoAcao("🔒 Travar tela") { FamiliaRepo.enviarComando(Prefs.codigoFamilia, idDispositivo, "travar") })
        botoes.addView(botaoAcao("📍 Localizar agora") { FamiliaRepo.enviarComando(Prefs.codigoFamilia, idDispositivo, "localizar") })
        botoes.addView(botaoAcao("🗺 Abrir no Google Maps", principal = false) { abrirNoMaps() })
        raiz.addView(botoes)

        setContentView(raiz)

        escutaDispositivo = FamiliaRepo.escutarDispositivos(Prefs.codigoFamilia) { lista ->
            val d = lista.find { it.id == idDispositivo } ?: return@escutarDispositivos
            runOnUiThread { atualizarMarcador(d) }
        }
        escutaHistorico = FamiliaRepo.escutarHistorico(Prefs.codigoFamilia, idDispositivo, 200) { pontos ->
            runOnUiThread { desenharHistorico(pontos) }
        }
        escutaAlertas = FamiliaRepo.escutarAlertas(Prefs.codigoFamilia, idDispositivo) { tipo, detalhe ->
            runOnUiThread { Toast.makeText(this, mensagemAlerta(tipo, detalhe), Toast.LENGTH_LONG).show() }
        }
    }

    private fun botaoAcao(rotulo: String, principal: Boolean = true, acao: () -> Unit) = Button(this).apply {
        text = rotulo; isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.parseColor(if (principal) "#39D98A" else "#1F232C")); cornerRadius = dp(14).toFloat()
        }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { acao() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
    }

    private fun atualizarMarcador(d: Dispositivo) {
        val lat = d.lat ?: return
        val lng = d.lng ?: return
        val ponto = GeoPoint(lat, lng)
        ultimoPonto = ponto
        if (marcador == null) {
            marcador = Marker(mapa).also { mapa.overlays.add(it) }
            mapa.controller.setCenter(ponto)
        }
        marcador?.position = ponto
        marcador?.title = d.nome
        mapa.invalidate()
    }

    private fun desenharHistorico(pontos: List<Pair<Double, Double>>) {
        mapa.overlays.removeAll { it is Polyline }
        if (pontos.size > 1) {
            val linha = Polyline().apply {
                setPoints(pontos.map { GeoPoint(it.first, it.second) })
                outlinePaint.color = Color.parseColor("#4F8CFF")
            }
            mapa.overlays.add(0, linha)
            mapa.invalidate()
        }
    }

    private fun abrirNoMaps() {
        val p = ultimoPonto ?: return
        val uri = Uri.parse("geo:${p.latitude},${p.longitude}?q=${p.latitude},${p.longitude}")
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
    }

    private fun mensagemAlerta(tipo: String, detalhe: String): String = when (tipo) {
        "desligando" -> "⚠️ O celular está sendo desligado. Última posição enviada."
        "bateria_critica" -> "⚠️ Bateria crítica. Última posição enviada."
        "chip_trocado" -> "⚠️ O chip (SIM) deste celular foi trocado."
        "protecao_desativada" -> "⚠️ A proteção deste celular foi desativada."
        "travar_falhou" -> "Não consegui travar a tela (administrador não ativo)."
        else -> detalhe.ifBlank { tipo }
    }

    override fun onResume() { super.onResume(); mapa.onResume() }
    override fun onPause() { super.onPause(); mapa.onPause() }
    override fun onDestroy() {
        super.onDestroy()
        escutaDispositivo?.remove(); escutaHistorico?.remove(); escutaAlertas?.remove()
    }
}

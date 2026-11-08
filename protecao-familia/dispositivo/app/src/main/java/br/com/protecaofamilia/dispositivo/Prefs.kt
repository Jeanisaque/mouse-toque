package br.com.protecaofamilia.dispositivo

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

/** Guarda as configurações deste celular: vínculo com a família, PIN, etc. */
object Prefs {
    private lateinit var prefs: SharedPreferences

    fun iniciar(contexto: Context) {
        if (::prefs.isInitialized) return
        prefs = contexto.getSharedPreferences("protecao_familia", Context.MODE_PRIVATE)
    }

    var codigoFamilia: String
        get() = prefs.getString("codigo_familia", "") ?: ""
        set(v) = prefs.edit().putString("codigo_familia", v).apply()

    var nomeDispositivo: String
        get() = prefs.getString("nome_dispositivo", "") ?: ""
        set(v) = prefs.edit().putString("nome_dispositivo", v).apply()

    val idDispositivo: String
        get() {
            var id = prefs.getString("id_dispositivo", null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString("id_dispositivo", id).apply()
            }
            return id
        }

    var simSalvo: String
        get() = prefs.getString("sim_salvo", "") ?: ""
        set(v) = prefs.edit().putString("sim_salvo", v).apply()

    val protegido: Boolean get() = codigoFamilia.isNotBlank() && temPin

    // --------------------------------------------------------------- PIN

    val temPin: Boolean get() = prefs.getString("pin_hash", null) != null

    fun definirPin(pin: String) {
        val sal = Random.nextBytes(16).joinToString("") { "%02x".format(it) }
        prefs.edit().putString("pin_sal", sal).putString("pin_hash", hash(pin, sal)).apply()
    }

    fun verificarPin(pin: String): Boolean {
        val sal = prefs.getString("pin_sal", null) ?: return false
        val hashSalvo = prefs.getString("pin_hash", null) ?: return false
        return hash(pin, sal) == hashSalvo
    }

    fun removerTudo() {
        prefs.edit().clear().apply()
    }

    private fun hash(pin: String, sal: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest((sal + pin).toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

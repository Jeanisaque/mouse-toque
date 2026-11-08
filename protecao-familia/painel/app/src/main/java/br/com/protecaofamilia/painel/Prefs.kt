package br.com.protecaofamilia.painel

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var prefs: SharedPreferences

    fun iniciar(contexto: Context) {
        if (::prefs.isInitialized) return
        prefs = contexto.getSharedPreferences("protecao_familia_painel", Context.MODE_PRIVATE)
    }

    var codigoFamilia: String
        get() = prefs.getString("codigo_familia", "") ?: ""
        set(v) = prefs.edit().putString("codigo_familia", v).apply()
}

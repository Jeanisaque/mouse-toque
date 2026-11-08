package br.com.protecaofamilia.painel

data class Dispositivo(
    val id: String,
    val nome: String,
    val lat: Double?,
    val lng: Double?,
    val bateria: Int,
    val atualizadoEm: Long?,
    val alarmeAtivo: Boolean
)

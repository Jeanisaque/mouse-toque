package br.com.protecaofamilia.painel

import kotlin.random.Random

/**
 * Código de família: longo e aleatório. Ele faz o papel de "senha" de acesso aos
 * dados no Firestore (não existe servidor próprio validando quem é quem), então
 * precisa ser difícil de adivinhar e nunca deve ser compartilhado em público.
 */
object CodigoUtil {
    private const val ALFABETO = "23456789ABCDEFGHJKMNPQRSTUVWXYZ" // sem 0,1,O,I pra não confundir

    fun gerar(): String {
        val bruto = (1..20).map { ALFABETO[Random.nextInt(ALFABETO.length)] }.joinToString("")
        return bruto.chunked(4).joinToString("-")
    }

    fun normalizar(codigo: String): String =
        codigo.uppercase().filter { it.isLetterOrDigit() || it == '-' }
}

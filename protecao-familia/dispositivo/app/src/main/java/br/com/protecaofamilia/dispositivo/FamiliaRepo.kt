package br.com.protecaofamilia.dispositivo

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

/** Comunicação com o Firestore: manda localização e alertas, escuta comandos do painel. */
object FamiliaRepo {
    private val db get() = FirebaseFirestore.getInstance()
    private val auth get() = FirebaseAuth.getInstance()

    private fun entrar(aoTerminar: (Boolean) -> Unit) {
        val usuario = auth.currentUser
        if (usuario != null) { aoTerminar(true); return }
        auth.signInAnonymously()
            .addOnSuccessListener { aoTerminar(true) }
            .addOnFailureListener { aoTerminar(false) }
    }

    private fun docDispositivo(codigo: String, idDispositivo: String) =
        db.collection("familias").document(codigo)
            .collection("dispositivos").document(idDispositivo)

    fun vincular(codigo: String, nomeDispositivo: String, idDispositivo: String, aoTerminar: (Boolean) -> Unit) {
        entrar { ok ->
            if (!ok) { aoTerminar(false); return@entrar }
            db.collection("familias").document(codigo)
                .set(mapOf("criadoEm" to FieldValue.serverTimestamp()), SetOptions.merge())
            docDispositivo(codigo, idDispositivo).set(
                mapOf(
                    "nome" to nomeDispositivo,
                    "vinculadoEm" to FieldValue.serverTimestamp(),
                    "alarmeAtivo" to false
                ),
                SetOptions.merge()
            ).addOnSuccessListener { aoTerminar(true) }
                .addOnFailureListener { aoTerminar(false) }
        }
    }

    fun enviarLocalizacao(codigo: String, idDispositivo: String, lat: Double, lng: Double, precisao: Float, bateria: Int) {
        if (codigo.isBlank()) return
        entrar { ok ->
            if (!ok) return@entrar
            val doc = docDispositivo(codigo, idDispositivo)
            val dados = mapOf(
                "lat" to lat, "lng" to lng, "precisao" to precisao,
                "bateria" to bateria, "atualizadoEm" to FieldValue.serverTimestamp()
            )
            doc.set(dados, SetOptions.merge())
            doc.collection("historico").add(dados + ("horario" to FieldValue.serverTimestamp()))
        }
    }

    fun enviarAlerta(codigo: String, idDispositivo: String, tipo: String, detalhe: String = "") {
        if (codigo.isBlank()) return
        entrar { ok ->
            if (!ok) return@entrar
            docDispositivo(codigo, idDispositivo).collection("alertas").add(
                mapOf("tipo" to tipo, "detalhe" to detalhe, "horario" to FieldValue.serverTimestamp())
            )
        }
    }

    fun marcarAlarme(codigo: String, idDispositivo: String, ativo: Boolean) {
        if (codigo.isBlank()) return
        docDispositivo(codigo, idDispositivo).set(mapOf("alarmeAtivo" to ativo), SetOptions.merge())
    }

    fun escutarComandos(codigo: String, idDispositivo: String, aoReceber: (id: String, tipo: String) -> Unit): ListenerRegistration {
        return docDispositivo(codigo, idDispositivo).collection("comandos")
            .whereEqualTo("atendido", false)
            .addSnapshotListener { snap, _ ->
                snap?.documentChanges?.forEach { mudanca ->
                    val tipo = mudanca.document.getString("tipo") ?: return@forEach
                    aoReceber(mudanca.document.id, tipo)
                }
            }
    }

    fun marcarComandoAtendido(codigo: String, idDispositivo: String, idComando: String) {
        docDispositivo(codigo, idDispositivo).collection("comandos").document(idComando)
            .set(mapOf("atendido" to true, "atendidoEm" to FieldValue.serverTimestamp()), SetOptions.merge())
    }
}

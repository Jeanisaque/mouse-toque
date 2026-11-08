package br.com.protecaofamilia.painel

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

object FamiliaRepo {
    private val db get() = FirebaseFirestore.getInstance()
    private val auth get() = FirebaseAuth.getInstance()

    private fun entrar(aoTerminar: (Boolean) -> Unit) {
        if (auth.currentUser != null) { aoTerminar(true); return }
        auth.signInAnonymously()
            .addOnSuccessListener { aoTerminar(true) }
            .addOnFailureListener { aoTerminar(false) }
    }

    fun criarFamilia(aoTerminar: (String?) -> Unit) {
        entrar { ok ->
            if (!ok) { aoTerminar(null); return@entrar }
            val codigo = CodigoUtil.gerar()
            db.collection("familias").document(codigo)
                .set(mapOf("criadoEm" to FieldValue.serverTimestamp()))
                .addOnSuccessListener { aoTerminar(codigo) }
                .addOnFailureListener { aoTerminar(null) }
        }
    }

    fun entrarNaFamilia(codigo: String, aoTerminar: (Boolean) -> Unit) {
        entrar { ok ->
            if (!ok) { aoTerminar(false); return@entrar }
            db.collection("familias").document(codigo).get()
                .addOnSuccessListener { doc -> aoTerminar(doc.exists()) }
                .addOnFailureListener { aoTerminar(false) }
        }
    }

    fun escutarDispositivos(codigo: String, aoAtualizar: (List<Dispositivo>) -> Unit): ListenerRegistration {
        return db.collection("familias").document(codigo).collection("dispositivos")
            .addSnapshotListener { snap, _ ->
                val lista = snap?.documents?.map { d ->
                    Dispositivo(
                        id = d.id,
                        nome = d.getString("nome") ?: d.id,
                        lat = d.getDouble("lat"),
                        lng = d.getDouble("lng"),
                        bateria = (d.getLong("bateria") ?: -1L).toInt(),
                        atualizadoEm = d.getTimestamp("atualizadoEm")?.toDate()?.time,
                        alarmeAtivo = d.getBoolean("alarmeAtivo") ?: false
                    )
                } ?: emptyList()
                aoAtualizar(lista)
            }
    }

    fun enviarComando(codigo: String, idDispositivo: String, tipo: String) {
        db.collection("familias").document(codigo).collection("dispositivos").document(idDispositivo)
            .collection("comandos").add(
                mapOf("tipo" to tipo, "atendido" to false, "criadoEm" to FieldValue.serverTimestamp())
            )
    }

    fun escutarHistorico(
        codigo: String, idDispositivo: String, limite: Long,
        aoAtualizar: (List<Pair<Double, Double>>) -> Unit
    ): ListenerRegistration {
        return db.collection("familias").document(codigo).collection("dispositivos").document(idDispositivo)
            .collection("historico")
            .orderBy("horario", Query.Direction.DESCENDING)
            .limit(limite)
            .addSnapshotListener { snap, _ ->
                val pontos = snap?.documents?.mapNotNull { d ->
                    val lat = d.getDouble("lat"); val lng = d.getDouble("lng")
                    if (lat != null && lng != null) lat to lng else null
                } ?: emptyList()
                aoAtualizar(pontos)
            }
    }

    fun escutarAlertas(
        codigo: String, idDispositivo: String,
        aoReceber: (tipo: String, detalhe: String) -> Unit
    ): ListenerRegistration {
        return db.collection("familias").document(codigo).collection("dispositivos").document(idDispositivo)
            .collection("alertas")
            .orderBy("horario", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snap, _ ->
                snap?.documentChanges?.forEach { c ->
                    if (c.type == DocumentChange.Type.ADDED) {
                        val tipo = c.document.getString("tipo") ?: return@forEach
                        aoReceber(tipo, c.document.getString("detalhe") ?: "")
                    }
                }
            }
    }
}

package com.printease.app

import android.content.Context
import android.net.Uri
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal val shopServices =
    linkedMapOf(
        "pvc-id" to "PVC ID",
        "print-edit" to "Print & edit",
        "photocopy" to "Photocopy",
        "bookbind" to "Bookbind",
        "scan" to "Scan",
        "tarpapel" to "Tarpapel",
        "school-supplies" to "School supplies",
        "laminate" to "Laminate",
        "rush-id" to "Rush ID",
        "vellum-magazine" to "Vellum / Magazine",
        "tarpaulin" to "Tarpaulin layout & CAD",
        "brochure-booklet" to "Brochure & Booklet",
    )
internal val serviceQuoteStates = setOf("Submitted", "Quoted", "Declined")
internal val serviceNextStatus =
    mapOf(
        "Approved" to "In progress",
        "In progress" to "Ready for pickup",
        "Ready for pickup" to "Completed",
    )

internal fun isIdService(service: String) = service == "pvc-id" || service == "rush-id"

internal data class ServiceRequest(
    val id: String,
    val ownerId: String,
    val customerName: String,
    val service: String,
    val quantity: Int,
    val details: String,
    val size: String,
    val sides: String,
    val designName: String?,
    val status: String,
    val quote: Long?,
    val pickup: String,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Long,
)

internal data class DeskMessage(
    val id: String,
    val senderId: String,
    val senderRole: String,
    val text: String,
    val createdAt: Long,
)

internal class ServiceDesk(context: Context, private val documents: Documents) {
    private val prefs = context.getSharedPreferences("service-desk-demo", Context.MODE_PRIVATE)
    private val changes = MutableStateFlow(0L)
    private val firestore
        get() = FirebaseFirestore.getInstance()

    private fun timestamp() =
        if (BuildConfig.DEMO) System.currentTimeMillis() else FieldValue.serverTimestamp()

    private fun Query.watch(): Flow<List<DocumentSnapshot>> = callbackFlow {
        val listener = addSnapshotListener { snapshot, error ->
            if (error != null) close(error) else if (snapshot != null) trySend(snapshot.documents)
        }
        awaitClose { listener.remove() }
    }

    private fun localRows(prefix: String) =
        prefs.all.entries.mapNotNull { (key, raw) ->
            if (key.startsWith(prefix))
                key.removePrefix(prefix) to JSONObject(raw as String).asMap()
            else null
        }

    fun requests(session: Session): Flow<List<ServiceRequest>> =
        if (BuildConfig.DEMO)
            changes.map {
                localRows("request/")
                    .map { (id, values) -> request(id, values) }
                    .filter { session.admin || it.ownerId == session.uid }
                    .sortedByDescending { it.updatedAt }
            }
        else {
            val requests = firestore.collection("serviceRequests")
            val query =
                if (session.admin) requests else requests.whereEqualTo("ownerId", session.uid)
            query.watch().map { rows ->
                rows
                    .mapNotNull { doc -> doc.data?.let { request(doc.id, it) } }
                    .sortedByDescending { it.updatedAt }
            }
        }

    fun messages(parent: String, id: String): Flow<List<DeskMessage>> =
        if (BuildConfig.DEMO)
            changes.map {
                localRows("message/$parent/$id/")
                    .map { (key, map) -> message(key, map) }
                    .sortedBy { it.createdAt }
                    .takeLast(100)
            }
        else
            firestore
                .collection(parent)
                .document(id)
                .collection("messages")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(100)
                .watch()
                .map { docs ->
                    docs.asReversed().mapNotNull { doc ->
                        doc.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.let {
                            message(doc.id, it)
                        }
                    }
                }

    suspend fun submit(
        session: Session,
        id: String,
        service: String,
        quantity: Int,
        details: String,
        size: String,
        sides: String,
        design: PdfFile?,
    ) {
        require(!session.admin && service in shopServices)
        require(quantity in 1..1000) { "Enter a quantity from 1 to 1000." }
        require(service != "tarpaulin" || size.isNotBlank()) { "Enter the dimensions and unit." }
        val data =
            mapOf(
                "ownerId" to session.uid,
                "customerName" to
                    session.name.ifBlank { session.email.ifBlank { "Customer" } }.take(200),
                "service" to service,
                "quantity" to quantity,
                "details" to details.trim(),
                "size" to size.trim(),
                "sides" to if (isIdService(service)) sides else "",
                "design" to
                    design?.let { mapOf("fileName" to it.name.take(255), "fileHash" to it.sha256) },
                "status" to "Submitted",
                "quote" to null,
                "pickup" to "",
                "createdAt" to timestamp(),
                "updatedAt" to timestamp(),
                "version" to 0L,
            )
        if (design != null) {
            if (BuildConfig.DEMO)
                withContext(Dispatchers.IO) {
                    File(design.path).copyTo(documents.file(id), overwrite = true)
                }
            else HostedDocuments.upload(design, {}, session.uid, id)
        }
        if (BuildConfig.DEMO) saveLocal("request/$id", data)
        else {
            val ref = firestore.collection("serviceRequests").document(id)
            firestore.runTransaction { tx -> if (!tx.get(ref).exists()) tx.set(ref, data) }.await()
        }
    }

    suspend fun update(
        session: Session,
        current: ServiceRequest,
        action: String,
        price: Long? = null,
        pickup: String = "",
    ) {
        val refKey = "request/${current.id}"
        fun updates(data: Map<String, Any?>): Map<String, Any> {
            val actual = request(current.id, data)
            check(actual.version == current.version) {
                "This request changed. Review the latest details and try again."
            }
            val change =
                when {
                    session.admin && action == "quote" && actual.status in serviceQuoteStates -> {
                        require(price != null && price in 1..200000000 && pickup.isNotBlank()) {
                            "Enter a total in whole pesos and a pickup schedule."
                        }
                        mapOf("status" to "Quoted", "quote" to price, "pickup" to pickup.trim())
                    }
                    session.admin && action == "progress" && actual.status in serviceNextStatus ->
                        mapOf("status" to serviceNextStatus.getValue(actual.status))
                    !session.admin &&
                        actual.ownerId == session.uid &&
                        actual.status == "Quoted" &&
                        action in listOf("Approved", "Declined") -> mapOf("status" to action)
                    else -> error("This action is unavailable. Review the current status.")
                }
            return change + mapOf("updatedAt" to timestamp(), "version" to actual.version + 1)
        }
        if (BuildConfig.DEMO) {
            val previous = JSONObject(checkNotNull(prefs.getString(refKey, null))).asMap()
            saveLocal(refKey, previous + updates(previous))
        } else {
            val doc = firestore.collection("serviceRequests").document(current.id)
            firestore
                .runTransaction { tx -> tx.update(doc, updates(checkNotNull(tx.get(doc).data))) }
                .await()
        }
    }

    suspend fun send(session: Session, parent: String, id: String, text: String) {
        require(text.isNotBlank() && text.length <= 1000) {
            "Enter a message of up to 1,000 characters."
        }
        val values =
            mapOf(
                "senderId" to session.uid,
                "senderRole" to if (session.admin) "Shop" else "Customer",
                "text" to text.trim(),
                "createdAt" to timestamp(),
            )
        if (BuildConfig.DEMO) saveLocal("message/$parent/$id/${UUID.randomUUID()}", values)
        else {
            val ref = firestore.collection(parent).document(id).collection("messages").document()
            firestore
                .runTransaction { tx -> if (!tx.get(ref).exists()) tx.set(ref, values) }
                .await()
        }
    }

    suspend fun loadDesign(id: String): File =
        if (BuildConfig.DEMO) documents.file(id)
        else HostedDocuments.downloadService(id, documents.file("design-${UUID.randomUUID()}"))

    suspend fun saveDesign(id: String, uri: Uri, context: Context) =
        withContext(Dispatchers.IO) {
            val file = loadDesign(id)
            try {
                checkNotNull(context.contentResolver.openOutputStream(uri)) {
                        "The destination is unavailable."
                    }
                    .use { output -> file.inputStream().use { it.copyTo(output) } }
            } finally {
                if (!BuildConfig.DEMO) file.delete()
            }
        }

    private fun saveLocal(key: String, data: Map<String, Any?>) {
        prefs.edit().putString(key, JSONObject(data).toString()).apply()
        changes.value++
    }

    private fun JSONObject.asMap(): Map<String, Any?> =
        keys().asSequence().associateWith { key ->
            opt(key).let { if (it is JSONObject) it.asMap() else it }
        }

    private fun Map<String, Any?>.text(key: String) = this[key] as? String ?: ""

    private fun Map<String, Any?>.number(key: String) = (this[key] as? Number)?.toLong()

    private fun Map<String, Any?>.time(key: String) =
        (this[key] as? Timestamp)?.toDate()?.time ?: number(key) ?: 0L

    private fun request(id: String, data: Map<String, Any?>) =
        ServiceRequest(
            id,
            data.text("ownerId"),
            data.text("customerName"),
            data.text("service"),
            data.number("quantity")?.toInt() ?: 1,
            data.text("details"),
            data.text("size"),
            data.text("sides"),
            (data["design"] as? Map<*, *>)?.get("fileName") as? String,
            data.text("status"),
            data.number("quote"),
            data.text("pickup"),
            data.time("createdAt"),
            data.time("updatedAt"),
            data.number("version") ?: 0L,
        )

    private fun message(id: String, data: Map<String, Any?>) =
        DeskMessage(
            id,
            data.text("senderId"),
            data.text("senderRole"),
            data.text("text"),
            data.time("createdAt"),
        )
}

package com.printease.app

import android.content.Context
import android.net.Uri
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
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
    val backName: String?,
    val removed: Boolean,
    val quoteDetails: ServiceQuote?,
    val status: String,
    val quote: Long?,
    val pickup: String,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Long,
)

internal data class ServiceQuote(val serviceFee: Long, val extrasFee: Long, val pickupAt: Long) {
    fun values() =
        mapOf("serviceFee" to serviceFee, "extrasFee" to extrasFee, "pickupAt" to pickupAt)
}

internal data class ServiceDraft(
    val id: String = "service-${UUID.randomUUID()}",
    val service: String = "pvc-id",
    val quantity: String = "1",
    val details: String = "",
    val size: String = "",
    val sides: String = "Single-sided",
    val front: PdfFile? = null,
    val back: PdfFile? = null,
)

internal data class DeskAlert(val kind: String, val id: String, val time: Long)

internal data class DeskMessage(
    val id: String,
    val senderId: String,
    val senderRole: String,
    val text: String,
    val createdAt: Long,
)

internal class ServiceDesk(context: Context, private val documents: Documents) {
    private val prefs = context.getSharedPreferences("service-desk-demo", Context.MODE_PRIVATE)
    private val drafts = context.getSharedPreferences("service-drafts", Context.MODE_PRIVATE)
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

    suspend fun enhancements(): Boolean {
        if (BuildConfig.DEMO) return true
        return try {
            firestore
                .collection("capabilities")
                .document("service-desk-v2")
                .get(Source.SERVER)
                .await()
            prefs.edit().putBoolean("capability-v2", true).apply()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: com.google.firebase.firestore.FirebaseFirestoreException) {
            if (
                error.code ==
                    com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
            ) {
                prefs.edit().remove("capability-v2").apply()
                false
            } else prefs.getBoolean("capability-v2", false)
        }
    }

    private fun threadKey(kind: String, id: String) = "$kind--$id"

    fun alerts(session: Session): Flow<List<DeskAlert>> {
        val field = if (session.admin) "customerAt" else "shopAt"
        if (BuildConfig.DEMO)
            return changes.map {
                val reads = localRows("seen/${session.uid}/").toMap()
                localRows("activity/")
                    .mapNotNull { (key, row) ->
                        val seen = reads[key]?.time("seenAt") ?: 0
                        val time = row.time(field)
                        if ((session.admin || row["ownerId"] == session.uid) && time > seen)
                            DeskAlert(row.text("kind"), row.text("id"), time)
                        else null
                    }
                    .sortedByDescending { it.time }
            }
        val activities =
            firestore
                .collection("threadActivity")
                .let { if (session.admin) it else it.whereEqualTo("ownerId", session.uid) }
                .watch()
        val reads =
            firestore.collection("users").document(session.uid).collection("threadReads").watch()
        return combine(activities, reads) { activity, receipts ->
            val seen =
                receipts.associate { it.id to (it.getTimestamp("seenAt")?.toDate()?.time ?: 0) }
            activity
                .mapNotNull { doc ->
                    val row = doc.data ?: return@mapNotNull null
                    val time = row.time(field)
                    if (time > (seen[doc.id] ?: 0))
                        DeskAlert(row.text("kind"), row.text("id"), time)
                    else null
                }
                .sortedByDescending { it.time }
        }
    }

    suspend fun markRead(session: Session, kind: String, id: String) {
        val key = threadKey(kind, id)
        if (BuildConfig.DEMO)
            saveLocal("seen/${session.uid}/$key", mapOf("seenAt" to System.currentTimeMillis()))
        else
            firestore
                .collection("users")
                .document(session.uid)
                .collection("threadReads")
                .document(key)
                .set(mapOf("seenAt" to FieldValue.serverTimestamp()))
                .await()
    }

    private fun activity(tx: Transaction, kind: String, id: String, owner: String, field: String) {
        tx.set(
            firestore.collection("threadActivity").document(threadKey(kind, id)),
            mapOf(
                "kind" to kind,
                "id" to id,
                "ownerId" to owner,
                field to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        )
    }

    private fun localActivity(kind: String, id: String, owner: String, field: String) {
        val key = "activity/" + threadKey(kind, id)
        val previous = prefs.getString(key, null)?.let { JSONObject(it).asMap() }.orEmpty()
        saveLocal(
            key,
            previous +
                mapOf(
                    "kind" to kind,
                    "id" to id,
                    "ownerId" to owner,
                    field to System.currentTimeMillis(),
                ),
        )
    }

    fun restoreDraft(uid: String): ServiceDraft {
        val saved = drafts.getString(uid, null) ?: return ServiceDraft()
        return runCatching {
                val row = JSONObject(saved).asMap()
                fun file(key: String): PdfFile? {
                    val file = row[key] as? Map<*, *> ?: return null
                    val path = file["path"] as? String ?: return null
                    if (!File(path).isFile) return null
                    return PdfFile(
                        path,
                        file["name"] as String,
                        (file["pages"] as Number).toInt(),
                        (file["bytes"] as Number).toLong(),
                        file["hash"] as String,
                    )
                }
                ServiceDraft(
                    row.text("id"),
                    row.text("service"),
                    row.text("quantity"),
                    row.text("details"),
                    row.text("size"),
                    row.text("sides"),
                    file("front"),
                    file("back"),
                )
            }
            .getOrElse { ServiceDraft() }
    }

    fun saveDraft(uid: String, draft: ServiceDraft) {
        fun values(file: PdfFile?) =
            file?.let {
                mapOf(
                    "path" to it.path,
                    "name" to it.name,
                    "pages" to it.pages,
                    "bytes" to it.bytes,
                    "hash" to it.sha256,
                )
            }
        drafts
            .edit()
            .putString(
                uid,
                JSONObject(
                        mapOf(
                            "id" to draft.id,
                            "service" to draft.service,
                            "quantity" to draft.quantity,
                            "details" to draft.details,
                            "size" to draft.size,
                            "sides" to draft.sides,
                            "front" to values(draft.front),
                            "back" to values(draft.back),
                        )
                    )
                    .toString(),
            )
            .apply()
    }

    fun clearDraft(uid: String) {
        drafts.edit().remove(uid).apply()
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
        back: PdfFile? = null,
    ) {
        require(!session.admin && service in shopServices)
        require(quantity in 1..1000) { "Enter a quantity from 1 to 1000." }
        require(service != "tarpaulin" || size.isNotBlank()) { "Enter the dimensions and unit." }
        val ref =
            if (BuildConfig.DEMO) null else firestore.collection("serviceRequests").document(id)
        if (
            ref?.get(Source.SERVER)?.await()?.exists() == true ||
                (BuildConfig.DEMO && prefs.contains("request/$id"))
        )
            return
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
            ) +
                if (back != null && isIdService(service) && sides == "Double-sided")
                    mapOf(
                        "designBack" to
                            mapOf("fileName" to back.name.take(255), "fileHash" to back.sha256)
                    )
                else emptyMap()
        suspend fun upload(pdf: PdfFile, target: String) {
            if (BuildConfig.DEMO)
                withContext(Dispatchers.IO) {
                    File(pdf.path).copyTo(documents.file(target), overwrite = true)
                }
            else HostedDocuments.upload(pdf, {}, session.uid, id)
        }
        if (design != null) upload(design, id)
        if (data.containsKey("designBack") && back != null) upload(back, "$id-back")
        if (BuildConfig.DEMO) saveLocal("request/$id", data)
        else {
            checkNotNull(ref)
            firestore.runTransaction { tx -> if (!tx.get(ref).exists()) tx.set(ref, data) }.await()
        }
    }

    suspend fun update(
        session: Session,
        current: ServiceRequest,
        action: String,
        price: Long? = null,
        pickup: String = "",
        quoteDetails: ServiceQuote? = null,
        enhanced: Boolean = false,
    ) {
        val refKey = "request/${current.id}"
        fun updates(data: Map<String, Any?>): Map<String, Any> {
            val actual = request(current.id, data)
            check(actual.version == current.version) {
                "This request changed. Review the latest details and try again."
            }
            val change =
                when {
                    session.admin &&
                        action == "quote" &&
                        (actual.status in serviceQuoteStates || actual.status == "Approved") -> {
                        require(price != null && price in 1..200000000 && pickup.isNotBlank()) {
                            "Enter a total in whole pesos and a pickup schedule."
                        }
                        if (quoteDetails != null)
                            require(
                                quoteDetails.serviceFee > 0 &&
                                    quoteDetails.extrasFee >= 0 &&
                                    quoteDetails.serviceFee + quoteDetails.extrasFee == price &&
                                    quoteDetails.pickupAt > System.currentTimeMillis() &&
                                    quoteDetails.pickupAt <=
                                        System.currentTimeMillis() + 180L * 86400000
                            ) {
                                "Enter a valid breakdown and a pickup time in the next 180 days."
                            }
                        mapOf("status" to "Quoted", "quote" to price, "pickup" to pickup.trim()) +
                            if (quoteDetails != null) mapOf("quoteDetails" to quoteDetails.values())
                            else emptyMap()
                    }
                    session.admin && action == "progress" && actual.status in serviceNextStatus ->
                        mapOf("status" to serviceNextStatus.getValue(actual.status))
                    !session.admin &&
                        actual.ownerId == session.uid &&
                        action == "Cancelled" &&
                        actual.status in serviceQuoteStates -> mapOf("status" to "Cancelled")
                    !session.admin &&
                        actual.ownerId == session.uid &&
                        actual.status == "Quoted" &&
                        action in listOf("Approved", "Declined") -> mapOf("status" to action)
                    else -> error("This action is unavailable. Review the current status.")
                }
            return change + mapOf("updatedAt" to timestamp(), "version" to actual.version + 1)
        }
        val alert =
            enhanced &&
                session.admin &&
                (action == "quote" || (action == "progress" && current.status == "In progress"))
        if (BuildConfig.DEMO) {
            val previous = JSONObject(checkNotNull(prefs.getString(refKey, null))).asMap()
            saveLocal(refKey, previous + updates(previous))
            if (alert) localActivity("serviceRequests", current.id, current.ownerId, "shopAt")
        } else {
            val doc = firestore.collection("serviceRequests").document(current.id)
            firestore
                .runTransaction { tx ->
                    tx.update(doc, updates(checkNotNull(tx.get(doc).data)))
                    if (alert)
                        activity(tx, "serviceRequests", current.id, current.ownerId, "shopAt")
                }
                .await()
        }
    }

    suspend fun send(
        session: Session,
        parent: String,
        id: String,
        text: String,
        ownerId: String,
        enhanced: Boolean,
    ) {
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
        val field = if (session.admin) "shopAt" else "customerAt"
        if (BuildConfig.DEMO) {
            saveLocal("message/$parent/$id/${UUID.randomUUID()}", values)
            if (enhanced) localActivity(parent, id, ownerId, field)
        } else {
            val ref = firestore.collection(parent).document(id).collection("messages").document()
            firestore
                .runTransaction { tx ->
                    if (!tx.get(ref).exists()) {
                        tx.set(ref, values)
                        if (enhanced) activity(tx, parent, id, ownerId, field)
                    }
                }
                .await()
        }
    }

    suspend fun loadDesign(id: String, back: Boolean = false): File =
        if (BuildConfig.DEMO) documents.file(if (back) "$id-back" else id)
        else
            HostedDocuments.downloadService(id, documents.file("design-${UUID.randomUUID()}"), back)

    suspend fun saveDesign(id: String, uri: Uri, context: Context, back: Boolean = false) =
        withContext(Dispatchers.IO) {
            val file = loadDesign(id, back)
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
            (data["designBack"] as? Map<*, *>)?.get("fileName") as? String,
            data.number("documentDeletedAt") != null,
            (data["quoteDetails"] as? Map<*, *>)?.let {
                val service = it["serviceFee"] as? Number ?: return@let null
                val extras = it["extrasFee"] as? Number ?: return@let null
                val pickup = it["pickupAt"] as? Number ?: return@let null
                ServiceQuote(service.toLong(), extras.toLong(), pickup.toLong())
            },
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

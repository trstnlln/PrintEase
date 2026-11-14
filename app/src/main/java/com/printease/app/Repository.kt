package com.printease.app

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

interface ShopRepository {
    val state: StateFlow<ShopState>

    suspend fun signIn(email: String, password: String, name: String? = null)

    suspend fun resetPassword(email: String)

    fun demoSignIn(admin: Boolean)

    fun signOut()

    fun refresh() {}

    suspend fun submit(id: String, pdf: PdfFile, options: PrintOptions, progress: (Float) -> Unit)

    suspend fun transition(id: String, next: OrderStatus, note: String = "")

    suspend fun document(order: PrintOrder): File

    fun close() {}
}

class DemoRepository(context: Context, private val documents: Documents) : ShopRepository {
    private val prefs = context.getSharedPreferences("printease-demo-v2", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(ShopState())
    override val state: StateFlow<ShopState> = mutable
    private val all = mutableListOf<PrintOrder>()

    init {
        val stored = prefs.getString("orders", null)
        if (stored != null)
            runCatching {
                    val array = JSONArray(stored)
                    repeat(array.length()) { i ->
                        val obj = array.getJSONObject(i)
                        all += PrintOrder.fromMap(obj.getString("id"), jsonMap(obj))
                    }
                }
                .onFailure { all.clear() }
        if (stored == null) {
            val now = System.currentTimeMillis()
            all +=
                PrintOrder(
                    "sample-1042",
                    "demo-student",
                    "Alex Student",
                    "Research proposal.pdf",
                    "",
                    PrintOptions(pages = 12, copies = 2, duplex = true),
                    now - 1500000,
                    OrderStatus.Printing,
                    1,
                    queueDay(),
                )
            all +=
                PrintOrder(
                    "sample-1043",
                    "demo-student",
                    "Alex Student",
                    "Laboratory activity.pdf",
                    "",
                    PrintOptions(pages = 4, color = true),
                    now - 500000,
                )
            save()
        }
        prefs.getString("role", null)?.let { demoSignIn(it == "admin") }
    }

    override fun demoSignIn(admin: Boolean) {
        prefs.edit().putString("role", if (admin) "admin" else "student").apply()
        mutable.value =
            ShopState(
                session =
                    if (admin) Session("demo-admin", "Campus Print Shop", "admin@demo.local", true)
                    else Session("demo-student", "Alex Student", "student@demo.local", false)
            )
        publish()
    }

    private fun publish() {
        val session = state.value.session
        mutable.value =
            mutable.value.copy(
                orders =
                    all.filter { session != null && (session.admin || it.ownerId == session.uid) }
                        .sortedByDescending { it.createdAt }
            )
    }

    private fun save() {
        prefs
            .edit()
            .putString(
                "orders",
                JSONArray(all.map { JSONObject(it.toMap() + ("id" to it.id)) }).toString(),
            )
            .apply()
    }

    override fun signOut() {
        prefs.edit().remove("role").apply()
        mutable.value = ShopState()
    }

    override suspend fun signIn(email: String, password: String, name: String?) =
        error("Use a demo role to explore. Real accounts are available in the live build.")

    override suspend fun resetPassword(email: String) =
        error("Password reset is only available in the live build.")

    override suspend fun submit(
        id: String,
        pdf: PdfFile,
        options: PrintOptions,
        progress: (Float) -> Unit,
    ) {
        val session = requireNotNull(state.value.session)
        check(!session.admin)
        if (all.any { it.id == id }) return
        require(pdf.pages == options.pages)
        withContext(Dispatchers.IO) { File(pdf.path).copyTo(documents.file(id), overwrite = true) }
        progress(1f)
        all += PrintOrder(id, session.uid, session.name, pdf.name, documents.file(id).path, options)
        save()
        publish()
    }

    override suspend fun transition(id: String, next: OrderStatus, note: String) {
        val session = requireNotNull(state.value.session)
        val index = all.indexOfFirst { it.id == id }
        check(index >= 0)
        val old = all[index]
        check(old.status.canMoveTo(next)) { "This order has already changed." }
        check(session.admin || (old.ownerId == session.uid && next == OrderStatus.Cancelled))
        require(next != OrderStatus.Rejected || note.trim().isNotEmpty()) {
            "Enter a reason for rejecting the request."
        }
        val day = queueDay()
        val number = (all.filter { it.queueDay == day }.maxOfOrNull { it.queue ?: 0 } ?: 0) + 1
        all[index] =
            old.copy(
                status = next,
                note = note.trim().take(500),
                queue = if (next == OrderStatus.Accepted) number else old.queue,
                queueDay = if (next == OrderStatus.Accepted) day else old.queueDay,
            )
        save()
        publish()
    }

    override suspend fun document(order: PrintOrder): File =
        withContext(Dispatchers.IO) {
            if (order.id.startsWith("sample-")) documents.sample(order.id, order.options.pages)
            else
                documents.file(order.id).also {
                    check(it.exists()) {
                        "This local document is no longer available. Submit a new request."
                    }
                }
        }
}

private fun jsonMap(obj: JSONObject): Map<String, Any?> =
    obj.keys().asSequence().associateWith { key ->
        when (val v = obj.get(key)) {
            JSONObject.NULL -> null
            is JSONObject -> jsonMap(v)
            else -> v
        }
    }

class FirebaseRepository(context: Context) : ShopRepository {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache = File(context.cacheDir, "previews").apply { mkdirs() }
    private val mutable = MutableStateFlow(ShopState(connecting = true))
    override val state: StateFlow<ShopState> = mutable
    private var listener: ListenerRegistration? = null
    private var sessionJob: Job? = null
    private val authListener =
        FirebaseAuth.AuthStateListener { firebaseAuth ->
            sessionJob?.cancel()
            listener?.remove()
            listener = null
            val user = firebaseAuth.currentUser
            if (user == null) mutable.value = ShopState()
            else
                sessionJob =
                    scope.launch {
                        mutable.value = ShopState(connecting = true)
                        try {
                            val token = withTimeout(20000) { user.getIdToken(true).await() }
                            val session =
                                Session(
                                    user.uid,
                                    user.displayName?.takeIf { it.isNotBlank() }
                                        ?: user.email.orEmpty().substringBefore('@'),
                                    user.email.orEmpty(),
                                    token.claims["admin"] == true,
                                )
                            mutable.value = ShopState(session = session)
                            var query =
                                db.collection("orders")
                                    .orderBy(
                                        "createdAt",
                                        com.google.firebase.firestore.Query.Direction.DESCENDING,
                                    )
                                    .limit(500)
                            if (!session.admin)
                                query =
                                    db.collection("orders")
                                        .whereEqualTo("ownerId", user.uid)
                                        .orderBy(
                                            "createdAt",
                                            com.google.firebase.firestore.Query.Direction.DESCENDING,
                                        )
                                        .limit(500)
                            listener =
                                query.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error
                                    ->
                                    if (error != null)
                                        mutable.value =
                                            mutable.value.copy(
                                                syncError =
                                                    "Orders could not sync. Check your connection and permissions."
                                            )
                                    else if (snapshot != null) {
                                        val orders =
                                            snapshot.documents.mapNotNull { doc ->
                                                runCatching {
                                                        PrintOrder.fromMap(
                                                            doc.id,
                                                            doc.data.orEmpty(),
                                                        )
                                                    }
                                                    .getOrNull()
                                            }
                                        mutable.value =
                                            mutable.value.copy(
                                                orders = orders.sortedByDescending { it.createdAt },
                                                cached = snapshot.metadata.isFromCache,
                                                syncError =
                                                    when {
                                                        orders.size != snapshot.size() ->
                                                            "Some orders need attention: their saved details could not be read."
                                                        snapshot.size() >= 500 ->
                                                            "Showing up to 500 orders. Archive old orders before extended use."
                                                        else -> null
                                                    },
                                            )
                                    }
                                }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            mutable.value =
                                ShopState(
                                    syncError =
                                        "Could not restore your account. Check your connection and sign in again."
                                )
                        }
                    }
        }

    init {
        auth.addAuthStateListener(authListener)
    }

    override suspend fun signIn(email: String, password: String, name: String?) {
        if (name == null)
            withTimeout(25000) { auth.signInWithEmailAndPassword(email.trim(), password).await() }
        else {
            val result =
                withTimeout(25000) {
                    auth.createUserWithEmailAndPassword(email.trim(), password).await()
                }
            result.user!!
                .updateProfile(
                    UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()
                )
                .await()
            // Re-read the profile after registration without invalidating Firebase's session.
            mutable.value =
                mutable.value.copy(session = mutable.value.session?.copy(name = name.trim()))
        }
    }

    override suspend fun resetPassword(email: String) {
        withTimeout(25000) { auth.sendPasswordResetEmail(email.trim()).await() }
    }

    override fun demoSignIn(admin: Boolean) =
        error("Demo role switching is not available in the live app.")

    override fun refresh() {
        authListener.onAuthStateChanged(auth)
    }

    override fun signOut() {
        auth.signOut()
        cache.listFiles()?.forEach { it.delete() }
    }

    override suspend fun submit(
        id: String,
        pdf: PdfFile,
        options: PrintOptions,
        progress: (Float) -> Unit,
    ) {
        val session = requireNotNull(state.value.session)
        check(!session.admin)
        require(pdf.pages == options.pages)
        val ref = db.collection("orders").document(id)
        // A stable ID survives retries and process recreation. Resolve uncertain writes first.
        val existing = withTimeout(15000) { ref.get(Source.SERVER).await() }
        if (existing.exists()) {
            val prior = PrintOrder.fromMap(id, existing.data.orEmpty())
            check(prior.status !in listOf(OrderStatus.Cancelled, OrderStatus.Rejected)) {
                "This request was closed. Check Orders, then discard this draft to start a new request."
            }
            check(prior.options == options) {
                "This request was already submitted with earlier print options. Check Orders before creating a new request."
            }
            return
        }
        val path = "documents/${session.uid}/$id.pdf"
        val blob = storage.reference.child(path)
        val metadata =
            try {
                withTimeout(15000) { blob.metadata.await() }
            } catch (e: StorageException) {
                if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) null else throw e
            }
        if (metadata != null)
            check(metadata.getCustomMetadata("sha256") == pdf.sha256) {
                "This draft changed. Choose the document again before retrying."
            }
        else {
            val task =
                blob.putFile(
                    Uri.fromFile(File(pdf.path)),
                    StorageMetadata.Builder()
                        .setContentType("application/pdf")
                        .setCustomMetadata("sha256", pdf.sha256)
                        .build(),
                )
            task.addOnProgressListener {
                progress(
                    if (it.totalByteCount > 0) it.bytesTransferred.toFloat() / it.totalByteCount
                    else 0f
                )
            }
            try {
                withTimeout(90000) { task.await() }
            } finally {
                if (!task.isComplete) task.cancel()
            }
        }
        val order =
            PrintOrder(id, session.uid, session.name.take(200), pdf.name.take(255), path, options)
        withTimeout(25000) { ref.set(order.toMap()).await() }
        // Never delete on uncertain writes: retrying uses this ID and resolves the result.
    }

    override suspend fun transition(id: String, next: OrderStatus, note: String) {
        val session = requireNotNull(state.value.session)
        withTimeout(25000) {
            db.runTransaction { tx ->
                    val ref = db.collection("orders").document(id)
                    val old =
                        PrintOrder.fromMap(id, tx.get(ref).data ?: error("Order no longer exists."))
                    check(old.status.canMoveTo(next)) { "Order changed. Review its latest status." }
                    check(
                        session.admin ||
                            (session.uid == old.ownerId && next == OrderStatus.Cancelled)
                    )
                    require(next != OrderStatus.Rejected || note.trim().isNotEmpty())
                    val updates =
                        mutableMapOf<String, Any>(
                            "status" to next.label,
                            "note" to note.trim().take(500),
                        )
                    if (next == OrderStatus.Accepted) {
                        val day = queueDay()
                        val counterRef = db.collection("counters").document(day)
                        val count = (tx.get(counterRef).getLong("last") ?: 0) + 1
                        tx.set(counterRef, mapOf("last" to count, "orderId" to id))
                        updates["queue"] = count
                        updates["queueDay"] = day
                    }
                    tx.update(ref, updates)
                }
                .await()
        }
    }

    override suspend fun document(order: PrintOrder): File =
        withContext(Dispatchers.IO) {
            val target = File(cache, "${order.id}.pdf")
            if (target.exists()) return@withContext target
            val blob = storage.reference.child(order.storagePath)
            val metadata = withTimeout(15000) { blob.metadata.await() }
            require(metadata.sizeBytes in 5..MAX_PDF_BYTES.toLong()) {
                "The document exceeds the allowed size."
            }
            val temp = File(cache, "${order.id}-${UUID.randomUUID()}.part")
            val task = blob.getFile(temp)
            try {
                withTimeout(60000) { task.await() }
                check(temp.renameTo(target))
                target
            } catch (e: Exception) {
                task.cancel()
                temp.delete()
                throw e
            }
        }

    override fun close() {
        listener?.remove()
        auth.removeAuthStateListener(authListener)
        scope.cancel()
    }
}

package com.printease.app

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
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

    suspend fun signInWithGoogle(idToken: String) {
        error("Google sign-in is available in the live app. Use a demo role to explore locally.")
    }

    suspend fun resetPassword(email: String)

    fun demoSignIn(admin: Boolean)

    fun signOut()

    fun refresh() {}

    suspend fun submit(
        id: String,
        pdf: PdfFile,
        options: PrintOptions,
        addons: List<String>? = null,
        attachments: List<AttachmentDraft> = emptyList(),
        progress: (Float) -> Unit,
    )

    suspend fun transition(id: String, next: OrderStatus, note: String = "")

    suspend fun reviewOrder(
        id: String,
        action: ReviewAction,
        version: Long,
        note: String = "",
        quote: Long? = null,
        draft: CorrectionDraft? = null,
        progress: (Float) -> Unit = {},
    ) {
        error("Order review is unavailable.")
    }

    suspend fun cleanupPdf(id: String): Boolean = error("PDF cleanup is unavailable.")

    suspend fun setPickupTime(id: String, time: Long?) {
        error("Pickup estimates are unavailable.")
    }

    suspend fun markPrinted(id: String, index: Int, checked: Boolean)

    suspend fun saveSettings(settings: ShopSettings)

    suspend fun document(order: PrintOrder, index: Int = 0): File

    fun close() {}
}

class DemoRepository(context: Context, private val documents: Documents) : ShopRepository {
    private val prefs = context.getSharedPreferences("printease-demo-v2", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(ShopState())
    override val state: StateFlow<ShopState> = mutable
    private val all = mutableListOf<PrintOrder>()
    private var settings =
        runCatching {
                prefs.getString("shopSettings", null)?.let {
                    ShopSettings.fromMap(jsonMap(JSONObject(it)))
                }
            }
            .getOrNull() ?: ShopSettings()

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
                    if (admin)
                        Session("demo-admin", "Nano Print Alangilan", "admin@demo.local", true)
                    else Session("demo-student", "Alex Student", "student@demo.local", false),
                reviewAvailable = true,
                paperSizesAvailable = true,
                printToolsAvailable = true,
            )
        publish()
    }

    private fun publish() {
        val session = state.value.session
        mutable.value =
            mutable.value.copy(
                settings = settings,
                orders =
                    all.filter { session != null && (session.admin || it.ownerId == session.uid) }
                        .sortedByDescending { it.createdAt },
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
        addons: List<String>?,
        attachments: List<AttachmentDraft>,
        progress: (Float) -> Unit,
    ) {
        options.requireBatchRanges(attachments)
        val extra = attachments.orderAttachments()
        val session = requireNotNull(state.value.session)
        check(!session.admin)
        all.firstOrNull { it.id == id }
            ?.let { prior ->
                check(
                    prior.options == options &&
                        prior.attachments == extra &&
                        prior.review?.addons.orEmpty() == addons.orEmpty() &&
                        prior.status !in listOf(OrderStatus.Cancelled, OrderStatus.Rejected)
                ) {
                    "This request was already submitted or closed. Check Orders before creating a new request."
                }
                return
            }
        require(pdf.pages == options.pages)
        requirePrintOptions(options, attachments, settings, true)
        check(settings.acceptingOrders) {
            "The shop has paused new requests. Please try again later."
        }
        withContext(Dispatchers.IO) { File(pdf.path).copyTo(documents.file(id), overwrite = true) }
        copyAttachments(id, attachments)
        progress(1f)
        all +=
            PrintOrder(
                id,
                session.uid,
                session.name,
                pdf.name,
                documents.file(id).path,
                options,
                pricing = settings.rates,
                review =
                    if (addons != null || needsPaperQuote(options.paper) || extra.isNotEmpty())
                        OrderReview(addons = addons.orEmpty())
                    else null,
                attachments = extra,
            )
        save()
        publish()
    }

    override suspend fun transition(id: String, next: OrderStatus, note: String) {
        val session = requireNotNull(state.value.session)
        val index = all.indexOfFirst { it.id == id }
        check(index >= 0)
        val old = all[index]
        check(next != OrderStatus.Accepted || old.canAccept) {
            "The student must approve the final price first."
        }
        check(
            next != OrderStatus.Ready ||
                old.printedFiles.isEmpty() ||
                old.printedFiles.size == old.attachments.size + 1
        ) {
            "Mark every file printed before pickup."
        }
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
                updatedAt = System.currentTimeMillis(),
                completedAt =
                    if (next == OrderStatus.Completed) System.currentTimeMillis()
                    else old.completedAt,
            )
        save()
        publish()
    }

    override suspend fun reviewOrder(
        id: String,
        action: ReviewAction,
        version: Long,
        note: String,
        quote: Long?,
        draft: CorrectionDraft?,
        progress: (Float) -> Unit,
    ) {
        val session = requireNotNull(state.value.session)
        val index = all.indexOfFirst { it.id == id }
        check(index >= 0) { "This request is unavailable." }
        val order = all[index]
        val review = order.updateReview(session, action, version, note, quote)
        val revised =
            if (action == ReviewAction.Revise) {
                val correction = requireNotNull(draft)
                correction.options.requireBatchRanges(correction.attachments)
                requirePrintOptions(correction.options, correction.attachments, settings, true)
                val pdf = correction.pdf
                require(pdf.pages == correction.options.pages)
                val target = documents.file("$id-revision-${review.version}")
                withContext(Dispatchers.IO) { File(pdf.path).copyTo(target, overwrite = true) }
                copyAttachments(id, correction.attachments)
                order.copy(
                    attachments = correction.attachments.orderAttachments(),
                    fileName = pdf.name.take(255),
                    storagePath = target.path,
                    options = correction.options,
                    pricing = settings.rates,
                )
            } else order
        all[index] = revised.copy(review = review, updatedAt = System.currentTimeMillis())
        save()
        publish()
        progress(1f)
    }

    override suspend fun markPrinted(id: String, index: Int, checked: Boolean) {
        check(state.value.session?.admin == true)
        val position = all.indexOfFirst { it.id == id }
        check(position >= 0) { "Refresh this request before updating its checklist." }
        val order = all[position]
        all[position] = order.updatePrinted(index, checked)
        save()
        publish()
    }

    override suspend fun setPickupTime(id: String, time: Long?) {
        check(state.value.session?.admin == true) { "Only the shop can set a pickup estimate." }
        require(validPickupTime(time)) { "Choose a pickup time within the next seven days." }
        val index = all.indexOfFirst { it.id == id }
        check(
            index >= 0 &&
                all[index].status in
                    listOf(OrderStatus.Pending, OrderStatus.Accepted, OrderStatus.Printing)
        ) {
            "This request no longer needs a pickup estimate."
        }
        all[index] = all[index].copy(expectedReadyAt = time, updatedAt = System.currentTimeMillis())
        save()
        publish()
    }

    override suspend fun saveSettings(settings: ShopSettings) {
        check(state.value.session?.admin == true)
        check(this.settings.version == settings.version) {
            "Shop details changed. Refresh and try again."
        }
        this.settings = settings.copy(version = settings.version + 1)
        prefs.edit().putString("shopSettings", JSONObject(this.settings.toMap()).toString()).apply()
        publish()
    }

    private fun attachmentFile(id: String, item: OrderAttachment, index: Int): File =
        documents.file("$id-attachment-$index-${item.fileHash}")

    private suspend fun copyAttachments(id: String, attachments: List<AttachmentDraft>) =
        withContext(Dispatchers.IO) {
            attachments.forEachIndexed { index, item ->
                File(item.pdf.path)
                    .copyTo(attachmentFile(id, item.metadata(), index + 1), overwrite = true)
            }
        }

    override suspend fun document(order: PrintOrder, index: Int): File =
        withContext(Dispatchers.IO) {
            if (index > 0) {
                val item =
                    order.attachments.getOrNull(index - 1) ?: error("This PDF is unavailable.")
                return@withContext attachmentFile(order.id, item, index).also {
                    check(it.exists()) { "This PDF is unavailable." }
                }
            }
            if (order.storagePath.isBlank() && order.id.startsWith("sample-"))
                documents.sample(order.id, order.options.pages)
            else
                File(order.storagePath).also {
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
            is JSONArray ->
                (0 until v.length()).map { i ->
                    val x = v.get(i)
                    if (x is JSONObject) jsonMap(x) else x
                }
            else -> v
        }
    }

class FirebaseRepository(private val context: Context) : ShopRepository {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val storage by lazy { FirebaseStorage.getInstance() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache = File(context.cacheDir, "previews").apply { mkdirs() }
    private val mutable = MutableStateFlow(ShopState(connecting = true))
    override val state: StateFlow<ShopState> = mutable
    private var listener: ListenerRegistration? = null
    private var sessionJob: Job? = null
    private var settingsListener: ListenerRegistration? = null
    private var generation = 0L
    private val authListener =
        FirebaseAuth.AuthStateListener { firebaseAuth ->
            val previous = mutable.value
            val currentGeneration = ++generation
            sessionJob?.cancel()
            settingsListener?.remove()
            settingsListener = null
            listener?.remove()
            listener = null
            val user = firebaseAuth.currentUser
            if (user == null) mutable.value = ShopState()
            else
                sessionJob =
                    scope.launch {
                        if (previous.session?.uid != user.uid)
                            mutable.value = ShopState(connecting = true)
                        try {
                            val token = withTimeout(20000) { user.getIdToken(true).await() }
                            val consoleAdmin =
                                runCatching {
                                        withTimeout(10000) {
                                            db.collection("admins")
                                                .document(user.uid)
                                                .get(Source.SERVER)
                                                .await()
                                                .getBoolean("enabled") == true
                                        }
                                    }
                                    .getOrDefault(false)
                            val session =
                                Session(
                                    user.uid,
                                    user.displayName?.takeIf { it.isNotBlank() }
                                        ?: user.email.orEmpty().substringBefore('@'),
                                    user.email.orEmpty(),
                                    token.claims["admin"] == true || consoleAdmin,
                                    user.photoUrl?.toString(),
                                    user.providerData.any { it.providerId == "google.com" },
                                )
                            if (
                                generation != currentGeneration || auth.currentUser?.uid != user.uid
                            )
                                return@launch
                            mutable.value =
                                if (
                                    previous.session?.uid == session.uid &&
                                        previous.session.admin == session.admin
                                )
                                    previous.copy(session = session, connecting = false)
                                else ShopState(session = session)
                            scope.launch {
                                val available =
                                    try {
                                        withTimeout(10000) {
                                            db.collection("capabilities")
                                                .document("print-review-v1")
                                                .get(Source.SERVER)
                                                .await()
                                        }
                                        true
                                    } catch (_: FirebaseFirestoreException) {
                                        false
                                    } catch (_: TimeoutCancellationException) {
                                        false
                                    }
                                if (
                                    generation == currentGeneration &&
                                        auth.currentUser?.uid == user.uid
                                )
                                    mutable.value = mutable.value.copy(reviewAvailable = available)
                            }
                            scope.launch {
                                val available =
                                    try {
                                        withTimeout(10000) {
                                            db.collection("capabilities")
                                                .document("paper-sizes-v1")
                                                .get(Source.SERVER)
                                                .await()
                                        }
                                        true
                                    } catch (_: FirebaseFirestoreException) {
                                        false
                                    } catch (_: TimeoutCancellationException) {
                                        false
                                    }
                                if (
                                    generation == currentGeneration &&
                                        auth.currentUser?.uid == user.uid
                                )
                                    mutable.value =
                                        mutable.value.copy(paperSizesAvailable = available)
                            }
                            scope.launch {
                                val available =
                                    try {
                                        withTimeout(10000) {
                                            db.collection("capabilities")
                                                .document("print-tools-v1")
                                                .get(Source.SERVER)
                                                .await()
                                        }
                                        true
                                    } catch (_: FirebaseFirestoreException) {
                                        false
                                    } catch (_: TimeoutCancellationException) {
                                        false
                                    }
                                if (
                                    generation == currentGeneration &&
                                        auth.currentUser?.uid == user.uid
                                )
                                    mutable.value =
                                        mutable.value.copy(printToolsAvailable = available)
                            }
                            if (PickupAlerts.enabled(context))
                                scope.launch { runCatching { PickupAlerts.register(context) } }
                            settingsListener =
                                db.collection("shopSettings")
                                    .document("current")
                                    .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error
                                        ->
                                        if (
                                            generation == currentGeneration &&
                                                auth.currentUser?.uid == user.uid
                                        ) {
                                            if (error != null)
                                                mutable.value =
                                                    mutable.value.copy(
                                                        settingsError =
                                                            "Shop details could not sync. Refresh before submitting a request."
                                                    )
                                            else if (snapshot != null) {
                                                runCatching { ShopSettings.fromMap(snapshot.data) }
                                                    .onSuccess {
                                                        mutable.value =
                                                            mutable.value.copy(
                                                                settings = it,
                                                                settingsError = null,
                                                                settingsCached =
                                                                    snapshot.metadata.isFromCache,
                                                            )
                                                    }
                                                    .onFailure {
                                                        mutable.value =
                                                            mutable.value.copy(
                                                                settingsError =
                                                                    "Shop details need attention. Contact the administrator."
                                                            )
                                                    }
                                            }
                                        }
                                    }
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
                                    if (generation != currentGeneration) return@addSnapshotListener
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
                            if (
                                generation == currentGeneration && auth.currentUser?.uid == user.uid
                            )
                                mutable.value =
                                    if (previous.session?.uid == user.uid)
                                        previous.copy(
                                            cached = true,
                                            syncError =
                                                "Could not refresh. Showing saved orders; reconnect and tap Refresh.",
                                        )
                                    else
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
            authListener.onAuthStateChanged(auth)
        }
    }

    override suspend fun signInWithGoogle(idToken: String) {
        require(idToken.isNotBlank()) { "Google did not return a credential. Please try again." }
        val credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null)
        withTimeout(25000) { auth.signInWithCredential(credential).await() }
        // Firebase validates the ID token; the usual auth listener loads trusted admin claims.
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
        addons: List<String>?,
        attachments: List<AttachmentDraft>,
        progress: (Float) -> Unit,
    ) {
        options.requireBatchRanges(attachments)
        val extra = attachments.orderAttachments()
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
            check(
                prior.options == options &&
                    prior.attachments == extra &&
                    prior.review?.addons.orEmpty() == addons.orEmpty() &&
                    (prior.documentStore != "hosted" || prior.fileHash == pdf.sha256)
            ) {
                "This request was already submitted with earlier print options. Check Orders before creating a new request."
            }
            return
        }
        val quoted = state.value.settings
        val currentSettings = currentShopSettings()
        check(currentSettings.acceptingOrders) {
            "The shop has paused new requests. Your draft is saved."
        }
        check(currentSettings.rates == quoted.rates && currentSettings.version == quoted.version) {
            "Shop details or rates changed. Review the updated total and try again."
        }
        requirePrintOptions(options, attachments, currentSettings, state.value.printToolsAvailable)
        val specialPaper = needsPaperQuote(options.paper) || extra.isNotEmpty()
        check(!specialPaper || state.value.paperSizesAvailable) {
            "This paper size is unavailable. Refresh and try again."
        }
        val review =
            if (addons != null || specialPaper) {
                check(state.value.reviewAvailable || specialPaper) {
                    "Order review is unavailable. Refresh and try again."
                }
                OrderReview(addons = addons.orEmpty())
            } else null
        val path =
            uploadFiles(pdf, attachments, progress, session.uid, id.takeIf { review != null })
        val order =
            PrintOrder(
                id,
                session.uid,
                session.name.take(200),
                pdf.name.take(255),
                path,
                options,
                pricing = currentSettings.rates,
                documentStore = "hosted",
                fileHash = pdf.sha256,
                review = review,
                attachments = extra,
            )
        withTimeout(25000) { ref.set(order.toMap()).await() }
        progress(1f)
        // Never delete on uncertain writes: retrying uses this ID and resolves the result.
    }

    override suspend fun transition(id: String, next: OrderStatus, note: String) {
        val session = requireNotNull(state.value.session)
        withTimeout(25000) {
            db.runTransaction { tx ->
                    val ref = db.collection("orders").document(id)
                    val old =
                        PrintOrder.fromMap(id, tx.get(ref).data ?: error("Order no longer exists."))
                    check(next != OrderStatus.Accepted || old.canAccept) {
                        "The student must approve the final price first."
                    }
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
                            "updatedAt" to System.currentTimeMillis(),
                        )
                    if (next == OrderStatus.Accepted) {
                        val day = queueDay()
                        val counterRef = db.collection("counters").document(day)
                        val count = (tx.get(counterRef).getLong("last") ?: 0) + 1
                        tx.set(counterRef, mapOf("last" to count, "orderId" to id))
                        updates["queue"] = count
                        updates["queueDay"] = day
                    }
                    if (next == OrderStatus.Completed)
                        updates["completedAt"] = System.currentTimeMillis()
                    tx.update(ref, updates)
                }
                .await()
        }
    }

    private suspend fun uploadFiles(
        pdf: PdfFile,
        attachments: List<AttachmentDraft>,
        progress: (Float) -> Unit,
        owner: String,
        orderId: String?,
    ): String {
        val files = listOf(pdf) + attachments.map { it.pdf }
        val paths =
            files.mapIndexed { index, file ->
                progress(index.toFloat() / files.size)
                try {
                    HostedDocuments.upload(
                        file,
                        { progress((index + it) / files.size) },
                        owner,
                        orderId,
                    )
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException || files.size == 1)
                        throw error
                    throw java.io.IOException(
                        "File ${index + 1} of ${files.size} (${file.name}) could not send. ${friendlyError(error)} Retry to continue.",
                        error,
                    )
                }
            }
        return paths.first()
    }

    private suspend fun currentShopSettings(): ShopSettings =
        ShopSettings.fromMap(
            withTimeout(15000) {
                    db.collection("shopSettings").document("current").get(Source.SERVER).await()
                }
                .data
        )

    override suspend fun reviewOrder(
        id: String,
        action: ReviewAction,
        version: Long,
        note: String,
        quote: Long?,
        draft: CorrectionDraft?,
        progress: (Float) -> Unit,
    ) {
        val session = requireNotNull(state.value.session)
        check(auth.currentUser?.uid == session.uid) { "Sign in to update this request." }
        val ref = db.collection("orders").document(id)
        val revisionChanges =
            if (action == ReviewAction.Revise) {
                val correction = requireNotNull(draft)
                correction.options.requireBatchRanges(correction.attachments)
                val pdf = correction.pdf
                val extra = correction.attachments.orderAttachments()
                require(pdf.pages == correction.options.pages)
                check(
                    (!needsPaperQuote(correction.options.paper) && extra.isEmpty()) ||
                        state.value.paperSizesAvailable
                ) {
                    "This paper size is unavailable. Refresh and try again."
                }
                val prior = withTimeout(15000) { ref.get(Source.SERVER).await() }
                val order =
                    PrintOrder.fromMap(id, prior.data ?: error("This request is unavailable."))
                if (
                    order.ownerId == session.uid &&
                        order.review?.stage == ReviewStage.Revised &&
                        order.review.version == version + 1 &&
                        order.fileHash == pdf.sha256 &&
                        order.fileName == pdf.name.take(255) &&
                        order.options == correction.options &&
                        order.attachments == extra
                )
                    return
                order.updateReview(session, action, version)
                val shop = currentShopSettings()
                check(shop.version == state.value.settings.version) {
                    "Shop rates changed. Review the new estimate."
                }
                requirePrintOptions(
                    correction.options,
                    correction.attachments,
                    shop,
                    state.value.printToolsAvailable,
                )
                val path = uploadFiles(pdf, correction.attachments, progress, session.uid, id)
                mapOf(
                    "fileName" to pdf.name.take(255),
                    "attachments" to extra.map { it.toMap() },
                    "storagePath" to path,
                    "documentStore" to "hosted",
                    "fileHash" to pdf.sha256,
                    "options" to correction.options.toMap(),
                    "pricing" to shop.rates.toMap(),
                    "estimatedCost" to
                        if (needsPaperQuote(correction.options.paper) || extra.isNotEmpty()) 0L
                        else correction.options.total(shop.rates),
                )
            } else emptyMap()
        withTimeout(25000) {
            db.runTransaction { transaction ->
                    check(auth.currentUser?.uid == session.uid) {
                        "Your account changed. Sign in again."
                    }
                    val current =
                        PrintOrder.fromMap(
                            id,
                            transaction.get(ref).data ?: error("This request is unavailable."),
                        )
                    val review = current.updateReview(session, action, version, note, quote)
                    transaction.update(
                        ref,
                        revisionChanges +
                            mapOf(
                                "review" to review.toMap(),
                                "updatedAt" to System.currentTimeMillis(),
                            ),
                    )
                }
                .await()
        }
        progress(1f)
    }

    override suspend fun cleanupPdf(id: String): Boolean {
        check(state.value.session?.admin == true) { "Only the shop can remove expired PDFs." }
        val removed = HostedDocuments.cleanup(id)
        if (removed)
            cache
                .listFiles()
                ?.filter { it.name == "$id.pdf" || it.name.startsWith("$id-") }
                ?.forEach { it.delete() }
        return removed
    }

    override suspend fun markPrinted(id: String, index: Int, checked: Boolean) {
        check(state.value.session?.admin == true && state.value.printToolsAvailable)
        val target = db.collection("orders").document(id)
        withTimeout(25000) {
            db.runTransaction { tx ->
                    val snap = tx.get(target)
                    val order = PrintOrder.fromMap(id, requireNotNull(snap.data))
                    val next = order.updatePrinted(index, checked)
                    tx.update(
                        target,
                        mapOf("printedFiles" to next.printedFiles, "updatedAt" to next.updatedAt),
                    )
                }
                .await()
        }
    }

    override suspend fun setPickupTime(id: String, time: Long?) {
        check(state.value.session?.admin == true) { "Only the shop can set a pickup estimate." }
        require(validPickupTime(time)) { "Choose a pickup time within the next seven days." }
        withTimeout(25000) {
            db.runTransaction { tx ->
                    val target = db.collection("orders").document(id)
                    val order =
                        PrintOrder.fromMap(
                            id,
                            tx.get(target).data ?: error("Order no longer exists."),
                        )
                    check(
                        order.status in
                            listOf(OrderStatus.Pending, OrderStatus.Accepted, OrderStatus.Printing)
                    ) {
                        "This request no longer needs a pickup estimate."
                    }
                    tx.update(
                        target,
                        mapOf("expectedReadyAt" to time, "updatedAt" to System.currentTimeMillis()),
                    )
                }
                .await()
        }
    }

    override suspend fun saveSettings(settings: ShopSettings) {
        check(state.value.session?.admin == true)
        withTimeout(25000) {
            db.runTransaction { tx ->
                    val ref = db.collection("shopSettings").document("current")
                    val old = ShopSettings.fromMap(tx.get(ref).data)
                    check(old.version == settings.version) {
                        "Shop details changed. Refresh and try again."
                    }
                    tx.set(ref, settings.copy(version = old.version + 1).toMap())
                }
                .await()
        }
    }

    override suspend fun document(order: PrintOrder, index: Int): File =
        withContext(Dispatchers.IO) {
            check(order.documentDeletedAt == null) {
                "This PDF was removed under the shop’s retention policy. Your order history is still saved."
            }
            val session = requireNotNull(state.value.session) { "Sign in before opening a PDF." }
            check(session.admin || session.uid == order.ownerId) {
                "You do not have access to this PDF."
            }
            val fileHash =
                if (index == 0) order.fileHash ?: "original"
                else
                    order.attachments.getOrNull(index - 1)?.fileHash
                        ?: error("This PDF is unavailable.")
            val target = File(cache, "${order.id}-$fileHash.pdf")
            if (target.exists()) return@withContext target
            if (order.documentStore == "hosted")
                return@withContext HostedDocuments.download(order, target, index)
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
        generation++
        settingsListener?.remove()
        listener?.remove()
        auth.removeAuthStateListener(authListener)
        scope.cancel()
    }
}

private fun requirePrintOptions(
    options: PrintOptions,
    attachments: List<AttachmentDraft>,
    shop: ShopSettings,
    tools: Boolean,
) {
    val choices = listOf(options) + attachments.map { it.options }
    require(tools || choices.none { it.actualSize }) {
        "Actual-size printing is unavailable. Choose Fit to paper or refresh."
    }
    require(choices.none { shop.paperStock.status(it.paper) == "out" }) {
        "A selected paper size is out of stock. Choose a different size."
    }
}

private fun PrintOrder.updatePrinted(index: Int, checked: Boolean): PrintOrder {
    require(status == OrderStatus.Printing && index in 0..attachments.size) {
        "Refresh this request before updating its checklist."
    }
    return copy(
        printedFiles =
            (if (checked) printedFiles + index else printedFiles - index).distinct().sorted(),
        updatedAt = System.currentTimeMillis(),
    )
}

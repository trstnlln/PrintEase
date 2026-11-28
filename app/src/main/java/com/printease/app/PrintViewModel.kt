package com.printease.app

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.storage.StorageException
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

class PrintViewModel(application: Application, private val saved: SavedStateHandle) :
    AndroidViewModel(application) {
    val documents = Documents(application)
    var setupError: String? = null
        private set

    private val repository: ShopRepository? =
        try {
            if (BuildConfig.DEMO) DemoRepository(application, documents)
            else {
                check(FirebaseApp.initializeApp(application) != null) {
                    "Firebase is not configured."
                }
                FirebaseRepository(application)
            }
        } catch (_: Exception) {
            setupError =
                "Add your Firebase Android configuration to app/google-services.json, sync Gradle, then run the liveDebug variant. To explore without Firebase, choose demoDebug."
            null
        }
    val shop = repository?.state ?: MutableStateFlow(ShopState())
    val authError = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val progress = MutableStateFlow(0f)
    val route = saved.getStateFlow("route", "home")
    val selectedId = saved.getStateFlow("selected", "")
    val pdf = MutableStateFlow(restorePdf())
    val attachments = MutableStateFlow(restoreAttachments())
    val options =
        MutableStateFlow(
            PrintOptions(
                saved["paper"] ?: "A4",
                pdf.value?.pages ?: 1,
                saved["copies"] ?: 1,
                saved["color"] ?: false,
                saved["duplex"] ?: false,
                runCatching { parsePageRanges(saved["pageRanges"] ?: "", pdf.value?.pages ?: 1) }
                    .getOrDefault(emptyList()),
                saved["actualSize"] ?: false,
                saved["slidesPerSide"] ?: 1,
            )
        )
    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()
    val addons = saved.getStateFlow("draftAddons", arrayListOf<String>())
    val revisionNote = saved.getStateFlow<String?>("revisionNote", null)
    val revisionId = saved.getStateFlow<String?>("revisionId", null)
    val submittedId = saved.getStateFlow<String?>("submittedId", null)

    fun toggleAddon(id: String) {
        val current = addons.value
        saved["draftAddons"] =
            ArrayList(
                PrintAddon.entries
                    .map { it.key }
                    .filter { if (it == id) it !in current else it in current }
            )
    }

    private var draftId: String
        get() = saved["draftId"] ?: UUID.randomUUID().toString().also { saved["draftId"] = it }
        set(value) {
            saved["draftId"] = value
        }

    private fun restorePdf(): PdfFile? {
        val path = saved.get<String>("pdfPath") ?: return null
        if (!File(path).exists()) return null
        return runCatching { documents.inspect(File(path), saved["pdfName"] ?: "Document.pdf") }
            .getOrNull()
    }

    private fun restoreAttachments(): List<AttachmentDraft> =
        saved.get<ArrayList<Bundle>>("attachments").orEmpty().take(MAX_ATTACHMENTS).mapNotNull {
            item ->
            runCatching {
                    val path = requireNotNull(item.getString("path"))
                    val pdf = documents.inspect(File(path), item.getString("name", "Document.pdf"))
                    val options =
                        PrintOptions(
                            item.getString("paper", "A4"),
                            pdf.pages,
                            item.getInt("copies", 1),
                            item.getBoolean("color"),
                            item.getBoolean("duplex"),
                            actualSize = item.getBoolean("actualSize"),
                            slidesPerSide = item.getInt("slidesPerSide", 1),
                        )
                    AttachmentDraft(
                        pdf,
                        options,
                        PrintOptionText(options).let { defaults ->
                            PrintOptionText(
                                item.getString("copiesText", defaults.copies),
                                item.getString("rangesText", defaults.ranges),
                                item.getString("paperWidth", defaults.width),
                                item.getString("paperHeight", defaults.height),
                            )
                        },
                    )
                }
                .getOrNull()
        }

    private fun saveAttachments(items: List<AttachmentDraft>) {
        attachments.value = items
        saved["attachments"] =
            ArrayList(
                items.map { item ->
                    Bundle().apply {
                        putString("path", item.pdf.path)
                        putString("name", item.pdf.name)
                        putString("paper", item.options.paper)
                        putInt("copies", item.options.copies)
                        putBoolean("color", item.options.color)
                        putBoolean("duplex", item.options.duplex)
                        putBoolean("actualSize", item.options.actualSize)
                        putInt("slidesPerSide", item.options.slidesPerSide)
                        putString("copiesText", item.input.copies)
                        putString("rangesText", item.input.ranges)
                        putString("paperWidth", item.input.width)
                        putString("paperHeight", item.input.height)
                    }
                }
            )
    }

    fun addFiles(uris: List<Uri>) = action {
        check(pdf.value != null && shop.value.paperSizesAvailable)
        val slots = MAX_ATTACHMENTS - attachments.value.size
        require(uris.size in 1..slots) { "Choose up to $slots more files." }
        saveAttachments(attachments.value + importFiles(uris).map { AttachmentDraft(it) })
    }

    private suspend fun importFiles(uris: List<Uri>): List<PdfFile> {
        val imported = mutableListOf<PdfFile>()
        try {
            for (uri in uris) imported.add(documents.import(uri))
            return imported
        } catch (error: Exception) {
            imported.forEach { File(it.path).delete() }
            throw error
        }
    }

    fun changeAttachment(index: Int, value: AttachmentDraft) {
        if (busy.value || attachments.value.getOrNull(index)?.pdf?.path != value.pdf.path) return
        saveAttachments(attachments.value.mapIndexed { i, item -> if (i == index) value else item })
    }

    fun resizeImage(index: Int, percent: Int) = action {
        if (index == 0) {
            val old = requireNotNull(pdf.value)
            val resized = documents.resizeImage(old, percent)
            pdf.value = resized
            saved["pdfPath"] = resized.path
            saved["pdfName"] = resized.name
            File(old.path).delete()
        } else {
            val files = attachments.value
            val old = files[index - 1]
            val resized = documents.resizeImage(old.pdf, percent)
            saveAttachments(
                files.mapIndexed { i, item ->
                    if (i == index - 1) item.copy(pdf = resized) else item
                }
            )
            File(old.pdf.path).delete()
        }
    }

    fun removePrintFile(index: Int) {
        if (busy.value || index < 0) return
        if (index > 0) {
            val item = attachments.value.getOrNull(index - 1) ?: return
            saveAttachments(attachments.value.filterIndexed { i, _ -> i != index - 1 })
            File(item.pdf.path).delete()
            return
        }
        val previous = pdf.value ?: return
        val next = attachments.value.firstOrNull()
        if (next == null) {
            setOptions(PrintOptions())
            pdf.value = null
            saved.remove<String>("pdfPath")
            saved.remove<String>("pdfName")
            saved.remove<String>("draftId")
        } else {
            setOptions(next.options)
            pdf.value = next.pdf
            saved["pdfPath"] = next.pdf.path
            saved["pdfName"] = next.pdf.name
            saveAttachments(attachments.value.drop(1))
        }
        File(previous.path).delete()
    }

    fun navigate(value: String) {
        if (!busy.value) saved["route"] = value
    }

    fun openOrder(id: String) {
        saved["selected"] = id
        navigate("detail")
    }

    fun demoLogin(admin: Boolean) {
        saved["submittedId"] = null as String?
        repository?.demoSignIn(admin)
        navigate("home")
    }

    fun refresh() {
        if (!busy.value) repository?.refresh()
    }

    fun signOut() = action {
        authError.value = null
        PickupAlerts.stop(getApplication())
        if (!BuildConfig.DEMO) runCatching { PickupAlerts.unregister() }
        PickupAlerts.clear(getApplication())
        repository?.signOut()
        saved["submittedId"] = null as String?
        discardDraft()
        saved["route"] = "home"
        if (!BuildConfig.DEMO) GoogleSignIn.clearSession(getApplication())
    }

    fun signInWithGoogle(context: android.content.Context) =
        action(
            "Google sign-in timed out. Check your connection and try again.",
            authentication = true,
        ) {
            check(!BuildConfig.DEMO) { "Select the liveDebug build to sign in with Google." }
            val token = GoogleSignIn.requestIdToken(context)
            repository!!.signInWithGoogle(token)
        }

    fun authenticate(email: String, password: String, name: String?) =
        action(authentication = true) {
            require(android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
                "Enter a valid email address."
            }
            require(password.length >= 8 || name == null) {
                "Choose a password with at least 8 characters."
            }
            require(password.isNotBlank()) { "Enter your password." }
            if (name != null)
                require(name.trim().length in 2..200) { "Enter your full name (2–200 characters)." }
            repository!!.signIn(email.trim(), password, name)
        }

    fun resetPassword(email: String) =
        action(authentication = true) {
            require(android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
                "Enter your email above, then tap Reset password."
            }
            repository!!.resetPassword(email)
            events.send(
                "If an account exists for this address, a password reset email will arrive shortly."
            )
        }

    fun pickFiles(uris: List<Uri>) = action {
        val capacity =
            if (pdf.value == null && shop.value.paperSizesAvailable) MAX_ATTACHMENTS + 1 else 1
        require(uris.size in 1..capacity) { "Choose up to $capacity files in this selection." }
        val files = importFiles(uris)
        val imported = files.first()
        val previous = pdf.value
        pdf.value = imported
        saved["pdfPath"] = imported.path
        saved["pdfName"] = imported.name
        if (files.size > 1)
            saveAttachments(attachments.value + files.drop(1).map { AttachmentDraft(it) })
        draftId = UUID.randomUUID().toString()
        setOptions(
            options.value.copy(pages = imported.pages, pageRanges = emptyList(), slidesPerSide = 1)
        )
        previous?.let { File(it.path).delete() }
    }

    fun setOptions(value: PrintOptions) {
        options.value = value
        saved["paper"] = value.paper
        saved["copies"] = value.copies
        saved["color"] = value.color
        saved["actualSize"] = value.actualSize
        saved["duplex"] = value.duplex
        saved["slidesPerSide"] = value.slidesPerSide
        saved["pageRanges"] = pageRangeLabel(value.pageRanges)
    }

    fun submit() = action {
        val document = requireNotNull(pdf.value) { "Choose a PDF first." }
        check(File(document.path).exists()) {
            "Your saved draft is unavailable. Choose the PDF again."
        }
        val options = options.value.copy(pages = document.pages)
        val revision = revisionId.value
        val progressUpdate: (Float) -> Unit = { progress.value = it }
        val temporary = mutableListOf<File>()
        val originalAttachments = attachments.value
        options.requireBatchRanges(originalAttachments)
        suspend fun prepare(source: PdfFile, choice: PrintOptions): PdfFile =
            if (choice.slidesPerSide == 1) source
            else documents.handout(source, choice).also { temporary.add(File(it.path)) }
        try {
            val printable = prepare(document, options)
            val outputOptions = options.handoutOptions()
            val extras =
                originalAttachments.map { item ->
                    item.metadata()
                    val output = prepare(item.pdf, item.options)
                    if (output == item.pdf) item
                    else AttachmentDraft(output, item.options.handoutOptions())
                }
            if (revision != null) {
                repository!!.reviewOrder(
                    revision,
                    ReviewAction.Revise,
                    requireNotNull(saved["revisionVersion"]),
                    draft = CorrectionDraft(printable, outputOptions, extras),
                    progress = progressUpdate,
                )
            } else {
                val reviewAvailable = shop.value.reviewAvailable
                check(reviewAvailable || addons.value.isEmpty()) {
                    "Add-ons are unavailable. Refresh before submitting."
                }
                repository!!.submit(
                    draftId,
                    printable,
                    outputOptions,
                    addons.value.takeIf { reviewAvailable },
                    extras,
                    progressUpdate,
                )
            }
        } finally {
            temporary.forEach { it.delete() }
        }
        saved["submittedId"] = revision ?: draftId
        discardDraft()
        saved["route"] = "orders"
        events.send(
            if (revision != null) "Correction sent. The shop will review it again."
            else "Request submitted. Follow its progress in Orders."
        )
    }

    fun discardDraft() {
        attachments.value.forEach { File(it.pdf.path).delete() }
        saveAttachments(emptyList())
        pdf.value?.let { File(it.path).delete() }
        pdf.value = null
        saved.remove<String>("pdfPath")
        saved.remove<String>("pdfName")
        saved.remove<String>("draftId")
        saved["draftAddons"] = arrayListOf<String>()
        saved["revisionId"] = null as String?
        saved["revisionNote"] = null as String?
        saved.remove<Long>("revisionVersion")
        options.value = PrintOptions()
        saved["paper"] = "A4"
        saved["copies"] = 1
        saved["color"] = false
        saved["actualSize"] = false
        saved["duplex"] = false
        saved["slidesPerSide"] = 1
        saved["pageRanges"] = ""
    }

    fun clearAuthError() {
        authError.value = null
    }

    fun savePrintFile(orderId: String, index: Int, destination: android.net.Uri) = action {
        check(shop.value.session?.admin == true)
        val order =
            shop.value.orders.find { it.id == orderId } ?: error("This request is unavailable.")
        val source = repository!!.document(order, index)
        withContext(Dispatchers.IO) {
            val output =
                getApplication<android.app.Application>()
                    .contentResolver
                    .openOutputStream(destination, "wt")
                    ?: error("The selected folder could not be opened.")
            output.use { stream -> source.inputStream().use { it.copyTo(stream) } }
        }
        events.send("PDF saved.")
    }

    fun markPrinted(id: String, index: Int, checked: Boolean) = action {
        repository!!.markPrinted(id, index, checked)
    }

    fun setPickupTime(id: String, time: Long?) = action {
        repository!!.setPickupTime(id, time)
        events.send(
            if (time == null) "Pickup estimate cleared."
            else "Pickup estimate saved. The customer can see it."
        )
    }

    fun reorder(order: PrintOrder) = action { prepareDraft(order, null) }

    fun editCorrection(order: PrintOrder) = action {
        check(
            order.status == OrderStatus.Pending && order.review?.stage == ReviewStage.Correction
        ) {
            "This request is no longer waiting for a correction."
        }
        val review = requireNotNull(order.review)
        if (
            revisionId.value == order.id &&
                saved.get<Long>("revisionVersion") == review.version &&
                pdf.value != null
        ) {
            saved["route"] = "request"
        } else {
            prepareDraft(order, RequestRevision(order.id, review.version, review.note))
        }
    }

    fun reviewOrder(id: String, action: ReviewAction, version: Long, note: String, quote: Long?) =
        action {
            repository!!.reviewOrder(id, action, version, note, quote)
            if (
                !BuildConfig.DEMO &&
                    action in listOf(ReviewAction.Quote, ReviewAction.RequestCorrection)
            ) {
                try {
                    HostedDocuments.notifyReady(id, version + 1)
                } catch (error: CancellationException) {
                    currentCoroutineContext().ensureActive()
                } catch (_: Exception) {
                    // The saved quote or correction stays visible in Orders.
                }
            }
            events.send(
                when (action) {
                    ReviewAction.Quote -> "Final price sent for approval."
                    ReviewAction.RequestCorrection -> "Correction requested."
                    ReviewAction.Approve -> "Price approved. The shop can now accept the order."
                    ReviewAction.Decline -> "Quote declined."
                    ReviewAction.Revise -> "Correction sent."
                }
            )
        }

    fun cleanupPdf(id: String) = action {
        val removed = repository!!.cleanupPdf(id)
        events.send(
            if (removed) "PDF removed. The order history is still saved."
            else "More PDF versions remain. Run cleanup again."
        )
    }

    private suspend fun prepareDraft(order: PrintOrder, revision: RequestRevision?) {
        val session = requireNotNull(shop.value.session)
        check(
            !session.admin &&
                session.uid == order.ownerId &&
                (revision != null || !order.status.active)
        ) {
            "Only your own eligible requests can be edited or repeated."
        }
        check(order.documentDeletedAt == null) {
            "This PDF is no longer available. Choose it again from your device."
        }
        val source = repository!!.document(order)
        val imported = documents.copyDraft(source, order.fileName)
        if (shop.value.session?.uid != session.uid) {
            File(imported.path).delete()
            error("Your account changed. Sign in again.")
        }
        val restoredOptions =
            try {
                order.options.copy(pages = imported.pages)
            } catch (error: Exception) {
                File(imported.path).delete()
                throw error
            }
        val preparedAttachments = mutableListOf<AttachmentDraft>()
        try {
            order.attachments.forEachIndexed { index, record ->
                val source = repository.document(order, index + 1)
                val copy = documents.copyDraft(source, record.fileName)
                if (copy.pages != record.options.pages) {
                    File(copy.path).delete()
                    error("An additional PDF no longer matches its page count.")
                }
                preparedAttachments += AttachmentDraft(copy, record.options)
            }
            check(shop.value.session?.uid == session.uid) { "Your account changed. Sign in again." }
        } catch (error: Exception) {
            File(imported.path).delete()
            preparedAttachments.forEach { File(it.pdf.path).delete() }
            throw error
        }
        attachments.value.forEach { File(it.pdf.path).delete() }
        saveAttachments(preparedAttachments)
        val previous = pdf.value
        pdf.value = imported
        saved["pdfPath"] = imported.path
        saved["pdfName"] = imported.name
        draftId = revision?.id ?: UUID.randomUUID().toString()
        saved["draftAddons"] = ArrayList(order.review?.addons.orEmpty())
        saved["revisionId"] = revision?.id
        saved["revisionVersion"] = revision?.version
        saved["revisionNote"] = revision?.note
        setOptions(restoredOptions)
        previous?.let { if (it.path != imported.path) File(it.path).delete() }
        saved["route"] = "request"
        events.send(
            if (revision != null) "Update the PDF or print options, then send your correction."
            else "Repeat draft ready. Review current prices before sending."
        )
    }

    fun saveSettings(settings: ShopSettings) = action {
        repository!!.saveSettings(settings)
        events.send("Shop details saved. Existing orders keep their quoted price.")
        saved["route"] = "account"
    }

    fun transition(id: String, status: OrderStatus, note: String = "") = action {
        if (status == OrderStatus.Completed) {
            require(
                note.replace(Regex("[^A-Za-z0-9]"), "").uppercase(Locale.ROOT) == collectionCode(id)
            ) {
                "Ask the customer for the matching collection code."
            }
        }
        repository!!.transition(id, status, if (status == OrderStatus.Completed) "" else note)
        if (!BuildConfig.DEMO && status == OrderStatus.Ready) {
            try {
                val failed = HostedDocuments.notifyReady(id)
                if (failed) {
                    events.send(
                        "Marked ready. Some background alerts could not send; the customer can still check Orders."
                    )
                    return@action
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                events.send(
                    "Marked ready. Background alerts could not send; the customer can still check Orders."
                )
                return@action
            }
        }
        events.send(
            when (status) {
                OrderStatus.Accepted -> "Accepted. A unique queue number has been assigned."
                OrderStatus.Cancelled -> "Request cancelled."
                else -> "Order updated: ${status.label}."
            }
        )
    }

    suspend fun loadDocument(order: PrintOrder, index: Int = 0): File =
        repository!!.document(order, index)

    private fun action(
        timeoutMessage: String =
            "The connection timed out. Check Orders before retrying; this draft will keep the same request ID.",
        authentication: Boolean = false,
        block: suspend () -> Unit,
    ) {
        if (busy.value) return
        busy.value = true
        if (authentication) authError.value = null
        progress.value = 0f
        viewModelScope.launch {
            try {
                block()
            } catch (e: TimeoutCancellationException) {
                if (authentication) authError.value = timeoutMessage
                else events.send(timeoutMessage)
            } catch (_: androidx.credentials.exceptions.GetCredentialCancellationException) {
                // Closing the account chooser is not a failed login.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (authentication) authError.value = friendlyError(e)
                else events.send(friendlyError(e))
            } finally {
                busy.value = false
                progress.value = 0f
            }
        }
    }

    override fun onCleared() {
        repository?.close()
        super.onCleared()
    }
}

fun friendlyError(e: Exception): String =
    when (e) {
        is com.google.firebase.FirebaseNetworkException ->
            "You appear to be offline. Check your internet connection and try again."
        is androidx.credentials.exceptions.NoCredentialException ->
            "No Google account is available. Add an account to this device and check that Google Play services are enabled."
        is androidx.credentials.exceptions.GetCredentialProviderConfigurationException ->
            "Google sign-in needs Google Play services. Use a supported phone or a Google Play emulator."
        is androidx.credentials.exceptions.GetCredentialException ->
            "Google sign-in could not finish. Check your connection and the Firebase Google provider, signing fingerprints, and updated configuration file."
        is FirebaseAuthException ->
            when {
                e.message.orEmpty().contains("CONFIGURATION_NOT_FOUND", ignoreCase = true) ->
                    "Firebase Authentication is not ready. Enable Google or Email/Password sign-in in your Firebase project."
                else ->
                    when (e.errorCode) {
                        "ERROR_OPERATION_NOT_ALLOWED" ->
                            "This sign-in method is disabled. Enable it in Firebase Authentication and try again."
                        "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" ->
                            "This email uses another sign-in method. Sign in with that method first."
                        "ERROR_INVALID_EMAIL" -> "Enter a valid email address."
                        "ERROR_EMAIL_ALREADY_IN_USE" ->
                            "This email is registered. Sign in or reset your password."
                        "ERROR_WEAK_PASSWORD" ->
                            "Choose a stronger password with at least 8 characters."
                        "ERROR_TOO_MANY_REQUESTS" ->
                            "Too many attempts. Please wait a moment before trying again."
                        else ->
                            "Could not sign in. Check your email, password, and internet connection."
                    }
            }
        is FirebaseFirestoreException ->
            "Could not save or sync the request. Check your connection and Firebase rules, then review Orders before retrying."
        is StorageException ->
            "The document could not transfer. Check your connection and retry this draft."
        else -> e.message?.takeIf { it.length <= 250 } ?: "Something went wrong. Please try again."
    }

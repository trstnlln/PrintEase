package com.printease.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.storage.StorageException
import java.io.File
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
    val busy = MutableStateFlow(false)
    val progress = MutableStateFlow(0f)
    val route = saved.getStateFlow("route", "home")
    val selectedId = saved.getStateFlow("selected", "")
    val pdf = MutableStateFlow(restorePdf())
    val options =
        MutableStateFlow(
            PrintOptions(
                saved["paper"] ?: "A4",
                pdf.value?.pages ?: 1,
                saved["copies"] ?: 1,
                saved["color"] ?: false,
                saved["duplex"] ?: false,
            )
        )
    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()
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

    fun navigate(value: String) {
        if (!busy.value) saved["route"] = value
    }

    fun openOrder(id: String) {
        saved["selected"] = id
        navigate("detail")
    }

    fun demoLogin(admin: Boolean) {
        repository?.demoSignIn(admin)
        navigate("home")
    }

    fun refresh() {
        if (!busy.value) repository?.refresh()
    }

    fun signOut() = action {
        repository?.signOut()
        discardDraft()
        saved["route"] = "home"
        if (!BuildConfig.DEMO) GoogleSignIn.clearSession(getApplication())
    }

    fun signInWithGoogle(context: android.content.Context) =
        action("Google sign-in timed out. Check your connection and try again.") {
            check(!BuildConfig.DEMO) { "Select the liveDebug build to sign in with Google." }
            val token = GoogleSignIn.requestIdToken(context)
            repository!!.signInWithGoogle(token)
        }

    fun authenticate(email: String, password: String, name: String?) = action {
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

    fun resetPassword(email: String) = action {
        require(android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
            "Enter your email above, then tap Reset password."
        }
        repository!!.resetPassword(email)
        events.send(
            "If an account exists for this address, a password reset email will arrive shortly."
        )
    }

    fun pick(uri: Uri) = action {
        val imported = documents.import(uri)
        pdf.value?.let { File(it.path).delete() }
        pdf.value = imported
        saved["pdfPath"] = imported.path
        saved["pdfName"] = imported.name
        draftId = UUID.randomUUID().toString()
        setOptions(options.value.copy(pages = imported.pages))
    }

    fun setOptions(value: PrintOptions) {
        options.value = value
        saved["paper"] = value.paper
        saved["copies"] = value.copies
        saved["color"] = value.color
        saved["duplex"] = value.duplex
    }

    fun submit() = action {
        val document = requireNotNull(pdf.value) { "Choose a PDF first." }
        check(File(document.path).exists()) {
            "Your saved draft is unavailable. Choose the PDF again."
        }
        repository!!.submit(draftId, document, options.value.copy(pages = document.pages)) {
            progress.value = it
        }
        discardDraft()
        saved["route"] = "orders"
        events.send("Request submitted. Follow its progress in Orders.")
    }

    fun discardDraft() {
        pdf.value?.let { File(it.path).delete() }
        pdf.value = null
        saved.remove<String>("pdfPath")
        saved.remove<String>("pdfName")
        saved.remove<String>("draftId")
        options.value = PrintOptions()
        saved["paper"] = "A4"
        saved["copies"] = 1
        saved["color"] = false
        saved["duplex"] = false
    }

    fun transition(id: String, status: OrderStatus, note: String = "") = action {
        repository!!.transition(id, status, note)
        events.send(
            when (status) {
                OrderStatus.Accepted -> "Accepted. A unique queue number has been assigned."
                OrderStatus.Cancelled -> "Request cancelled."
                else -> "Order updated: ${status.label}."
            }
        )
    }

    suspend fun loadDocument(order: PrintOrder): File = repository!!.document(order)

    private fun action(
        timeoutMessage: String =
            "The connection timed out. Check Orders before retrying; this draft will keep the same request ID.",
        block: suspend () -> Unit,
    ) {
        if (busy.value) return
        busy.value = true
        progress.value = 0f
        viewModelScope.launch {
            try {
                block()
            } catch (e: TimeoutCancellationException) {
                events.send(timeoutMessage)
            } catch (_: androidx.credentials.exceptions.GetCredentialCancellationException) {
                // Closing the account chooser is not a failed login.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                events.send(friendlyError(e))
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

package com.printease.app

import android.annotation.SuppressLint
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.withTimeoutOrNull

/** Only Google-issued ID tokens are handed to Firebase. Tokens are never logged or persisted. */
object GoogleSignIn {
    @SuppressLint("DiscouragedApi") // Generated resource is optional in demo/unconfigured builds.
    fun webClientId(context: Context): String {
        val id =
            context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        val value = if (id == 0) "" else context.getString(id)
        return validateClientId(value)
    }

    fun validateClientId(value: String): String {
        val clientId = value.trim()
        check(clientId.endsWith(".apps.googleusercontent.com") && clientId.length > 30) {
            "Google sign-in needs setup. Enable Google in Firebase Authentication, add your liveDebug SHA fingerprints, and replace app/google-services.json with a fresh download."
        }
        return clientId
    }

    @SuppressLint("CredentialManagerSignInWithGoogle") // Response types are checked in idToken().
    fun request(clientId: String): GetCredentialRequest =
        GetCredentialRequest.Builder()
            .addCredentialOption(
                GetSignInWithGoogleOption.Builder(validateClientId(clientId)).build()
            )
            .build()

    suspend fun requestIdToken(context: Context): String {
        // Use the Activity context supplied by the button for the system account chooser.
        val result =
            CredentialManager.create(context).getCredential(context, request(webClientId(context)))
        return idToken(result.credential)
    }

    fun idToken(credential: androidx.credentials.Credential): String {
        check(
            credential is CustomCredential &&
                credential.type in
                    listOf(
                        GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
                        GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL,
                    )
        ) {
            "Google returned an unsupported credential. Please try again."
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }

    suspend fun clearSession(context: Context) {
        // This clears the app's credential session; it does not remove the phone's Google account.
        withTimeoutOrNull(5000) {
            try {
                CredentialManager.create(context)
                    .clearCredentialState(ClearCredentialStateRequest())
            } catch (_: ClearCredentialException) {
                /* Firebase is already signed out. */
            }
        }
    }
}

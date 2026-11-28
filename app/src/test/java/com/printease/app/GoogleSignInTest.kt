package com.printease.app

import android.os.Bundle
import androidx.credentials.CustomCredential
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuthException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleSignInTest {
    private val client = "123456789012-build-fixture.apps.googleusercontent.com"

    @Test
    fun missingClientIdExplainsFirebaseSetup() {
        val error = runCatching { GoogleSignIn.request("") }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("google-services.json"))
    }

    @Test
    fun explicitGoogleRequestUsesWebClientWithoutAuthorizedAccountFilter() {
        val option = GoogleSignIn.request(client).credentialOptions.single()
        assertTrue(option is GetSignInWithGoogleOption)
        assertEquals(client, (option as GetSignInWithGoogleOption).serverClientId)
    }

    @Test
    fun unsupportedCredentialsAreNotSentToFirebase() {
        assertTrue(
            runCatching { GoogleSignIn.idToken(CustomCredential("another-provider", Bundle())) }
                .isFailure
        )
    }

    @Test
    fun googleCredentialExtractsTokenForFirebaseValidation() {
        val credential =
            GoogleIdTokenCredential.Builder()
                .setId("student@gmail.com")
                .setIdToken("test-token-not-real")
                .build()
        assertEquals("test-token-not-real", GoogleSignIn.idToken(credential))
    }

    @Test
    fun missingAccountHasUsefulGuidance() {
        assertTrue(friendlyError(NoCredentialException()).contains("Add an account"))
    }

    @Test
    fun configurationErrorDoesNotExposeRawBackendMessage() {
        val error =
            FirebaseAuthException(
                "ERROR_INTERNAL_ERROR",
                "An internal error has occurred. [ CONFIGURATION_NOT_FOUND ]",
            )
        assertTrue(friendlyError(error).contains("Firebase Authentication is not ready"))
        assertFalse(friendlyError(error).contains("CONFIGURATION_NOT_FOUND"))
    }

    @Test
    fun explicitSignInSubtypeIsAlsoRecognized() {
        val google =
            GoogleIdTokenCredential.Builder()
                .setId("student@gmail.com")
                .setIdToken("test-token-not-real")
                .build()
        val credential =
            CustomCredential(
                GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL,
                google.data,
            )
        assertEquals("test-token-not-real", GoogleSignIn.idToken(credential))
    }
}

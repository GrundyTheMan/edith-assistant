package com.edith.app.google

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.tasks.await

/** "Sign in with Google" through Android's Credential Manager (C17 §1). */
class CredentialManagerSignIn(context: Context) : GoogleSignIn {
    private val app = context.applicationContext

    override suspend fun signIn(activity: Activity, webClientId: String): GoogleSignIn.Result {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).build())
            .build()
        return try {
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                GoogleSignIn.Result.SignedIn(google.id, google.displayName)
            } else {
                GoogleSignIn.Result.Failed("Google didn't return an account.")
            }
        } catch (e: GetCredentialCancellationException) {
            GoogleSignIn.Result.Cancelled
        } catch (e: NoCredentialException) {
            Log.w(LogTag.Google, "No Google account to sign in with", e)
            GoogleSignIn.Result.Failed("No Google account is set up on this phone. Add one in Android Settings → Passwords & accounts.")
        } catch (e: GetCredentialException) {
            Log.w(LogTag.Google, "Google sign-in failed (${e.type})", e)
            GoogleSignIn.Result.Failed("Google sign-in didn't work: ${e.message ?: e.type}")
        } catch (e: GoogleIdTokenParsingException) {
            Log.w(LogTag.Google, "Couldn't read Google's sign-in answer", e)
            GoogleSignIn.Result.Failed("Google's answer couldn't be read.")
        }
    }

    override suspend fun signOut() {
        CredentialManager.create(app).clearCredentialState(ClearCredentialStateRequest())
    }
}

/**
 * Access to one account's Calendar and Gmail through Google's `AuthorizationClient` (C17 §2): tokens stay in Play
 * services. C22: the request names the account (`setAccount`), so every Google account on the phone can use it.
 */
class PlayServicesAuthorizer(context: Context, private val email: String) : GoogleAuthorizer {
    private val client = Identity.getAuthorizationClient(context.applicationContext)

    override suspend fun authorize(scopes: Set<GoogleScope>): GoogleAuthorizer.Result = try {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(scopes.map { Scope(it.uri) })
            .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
            .build()
        val result = client.authorize(request).await()
        when {
            result.hasResolution() -> GoogleAuthorizer.Result.NeedsConsent(result.pendingIntent)
            result.accessToken != null -> GoogleAuthorizer.Result.Granted(result.accessToken!!)
            else -> GoogleAuthorizer.Result.Failed("Google gave no access.")
        }
    } catch (e: ApiException) {
        Log.w(LogTag.Google, "Google authorization failed (status ${e.statusCode})", e)
        GoogleAuthorizer.Result.Failed(messageFor(e))
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(LogTag.Google, "Google authorization failed", e)
        GoogleAuthorizer.Result.Failed("Google didn't answer: ${e.message ?: e::class.simpleName}")
    }

    override fun fromConsent(data: Intent?): GoogleAuthorizer.Result = try {
        val result = client.getAuthorizationResultFromIntent(data)
        result.accessToken?.let { GoogleAuthorizer.Result.Granted(it) } ?: GoogleAuthorizer.Result.Failed("No access was granted.")
    } catch (e: ApiException) {
        Log.w(LogTag.Google, "Consent screen returned no access (status ${e.statusCode})", e)
        GoogleAuthorizer.Result.Failed(messageFor(e))
    }

    override suspend fun clearToken(token: String) {
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
            .onFailure { Log.w(LogTag.Google, "Couldn't clear a refused token", it) }
    }

    override suspend fun revoke(email: String) {
        client.revokeAccess(
            RevokeAccessRequest.builder()
                .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
                .setScopes(GoogleScope.entries.map { Scope(it.uri) })
                .build(),
        ).await()
    }

    private fun messageFor(e: ApiException): String = when (e.statusCode) {
        16 -> "Access wasn't approved."   // CommonStatusCodes.CANCELED
        7 -> "No connection to Google — try again."   // NETWORK_ERROR
        10 -> "Google doesn't recognise this build of Edith (check the OAuth clients in docs/google-setup.md)."   // DEVELOPER_ERROR
        else -> "Google said no (code ${e.statusCode})."
    }
}

/** Android's account type for Google accounts. */
private const val GOOGLE_ACCOUNT_TYPE = "com.google"

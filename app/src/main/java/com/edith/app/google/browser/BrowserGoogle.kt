package com.edith.app.google.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.secrets.SecretStore
import com.edith.app.core.time.Clock
import com.edith.app.google.GoogleAuthorizer
import com.edith.app.google.GoogleScope
import com.edith.app.google.GoogleSignIn
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Google sign-in and access through the browser, for devices without Play services (C17b, D16): Google's page in
 * the real browser, the code back on [LoopbackReceiver] (or pasted), and a refresh token kept encrypted in
 * [SecretStore]. Access tokens stay in memory. Nothing secret is logged.
 */
class BrowserGoogle(
    private val oauth: BrowserOAuth,
    private val secrets: SecretStore,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
    /** `${applicationId}://signed-in`: the loopback page's link back to Edith (§4). */
    private val backLink: String,
    /** The account to suggest on Google's page, when one is known. */
    private val knownEmail: suspend () -> String? = { null },
    /** Opens [url] in the browser; throws [ActivityNotFoundException] when there's none. */
    private val openBrowser: (Activity, String) -> Unit = ::startBrowser,
    /** Opens the loopback listener at a port (0: any). */
    private val openReceiver: (Int) -> LoopbackReceiver = { LoopbackReceiver.open(dispatchers.io, backLink, it) },
    private val loopbackTimeout: Duration = 5.minutes,
) : GoogleSignIn, GoogleAuthorizer {

    private sealed interface Input {
        data class Address(val address: String) : Input
        data object Cancel : Input
    }

    private class Cached(val token: String, val expiresAt: Instant, val scopes: Set<String>)

    @Serializable
    private data class Pending(val verifier: String, val state: String, val redirectUri: String, val startedAtMillis: Long)

    private val _waiting = MutableStateFlow(false)
    /** True while Edith waits for the browser: Settings shows the "Finish signing in" dialog. */
    val waiting: StateFlow<Boolean> = _waiting.asStateFlow()

    @Volatile private var inputs: Channel<Input>? = null
    @Volatile private var cached: Cached? = null
    private val refreshLock = Mutex()
    private val signInLock = Mutex()

    val isConfigured: Boolean get() = oauth.isConfigured

    suspend fun hasRefreshToken(): Boolean = secrets.get(REFRESH_TOKEN_KEY) != null

    /** The waiting dialog's **Use this address**. */
    fun paste(address: String) {
        inputs?.trySend(Input.Address(address))
    }

    /** The waiting dialog's **Cancel**. */
    fun cancel() {
        inputs?.trySend(Input.Cancel)
    }

    /** Opens Google's sign-in page and waits for the code (loopback or pasted), the owner's cancel, or the timeout. */
    override suspend fun signIn(activity: Activity, webClientId: String): GoogleSignIn.Result {
        if (!oauth.isConfigured) return GoogleSignIn.Result.Failed(NOT_SET_UP)
        if (!signInLock.tryLock()) return GoogleSignIn.Result.Cancelled   // already waiting: the dialog is up
        try {
            val receiver = try {
                openReceiver(0)
            } catch (e: IOException) {
                Log.w(LogTag.Google, "Browser sign-in: couldn't open the loopback listener (${e::class.simpleName})")
                return GoogleSignIn.Result.Failed(NO_LISTENER)
            }
            val pkce = oauth.pkce()
            val pending = Pending(pkce.verifier, oauth.newState(), receiver.redirectUri, clock.now().toEpochMilli())
            savePending(pending)   // survives Edith being killed while the owner is in the browser
            val url = oauth.authUrl(pending.redirectUri, pkce.challenge, pending.state, GoogleScope.entries.toSet(), knownEmail())
            val channel = beginWaiting()   // before the browser opens, so an early paste or cancel isn't lost
            try {
                openBrowser(activity, url)
            } catch (e: ActivityNotFoundException) {
                Log.w(LogTag.Google, "Browser sign-in: no browser on this device")
                endWaiting(channel)
                receiver.close()
                clearPending()
                return GoogleSignIn.Result.Failed(NO_BROWSER)
            }
            Log.i(LogTag.Google, "Browser sign-in: waiting for Google")
            return waitFor(pending, receiver, loopbackTimeout, channel)
        } finally {
            signInLock.unlock()
        }
    }

    /**
     * A sign-in left pending by an earlier run of Edith (killed while the owner was in the browser): waits for it
     * again, on the same port if it's free, else for a pasted address. Null when there's none (or it's too old).
     */
    suspend fun resumePending(): GoogleSignIn.Result? {
        val pending = loadPending() ?: return null
        val age = (clock.now().toEpochMilli() - pending.startedAtMillis).milliseconds
        if (age < Duration.ZERO || age >= PENDING_LIFETIME) {
            clearPending()
            return null
        }
        if (!signInLock.tryLock()) return null
        try {
            val port = pending.redirectUri.toHttpUrlOrNull()?.port
            val receiver = port?.let { p ->
                try { openReceiver(p) } catch (e: IOException) { null }   // taken: the paste still works
            }
            Log.i(LogTag.Google, "Browser sign-in: resuming a pending sign-in (listening again: ${receiver != null})")
            return waitFor(pending, receiver, PENDING_LIFETIME - age, beginWaiting())
        } finally {
            signInLock.unlock()
        }
    }

    private fun beginWaiting(): Channel<Input> = Channel<Input>(Channel.UNLIMITED).also {
        inputs = it
        _waiting.value = true
    }

    private fun endWaiting(channel: Channel<Input>) {
        inputs = null
        channel.close()
        _waiting.value = false
    }

    private suspend fun waitFor(pending: Pending, receiver: LoopbackReceiver?, timeout: Duration, channel: Channel<Input>): GoogleSignIn.Result {
        try {
            val input = withTimeoutOrNull(timeout) {
                coroutineScope {
                    val listening = receiver?.let { r -> launch { r.await(timeout)?.let { channel.send(Input.Address(it)) } } }
                    try { channel.receive() } finally { listening?.cancel() }
                }
            }
            return when (input) {
                null -> {
                    Log.w(LogTag.Google, "Browser sign-in: timed out")
                    clearPending()
                    GoogleSignIn.Result.Failed(TIMED_OUT)
                }
                Input.Cancel -> {
                    clearPending()
                    GoogleSignIn.Result.Cancelled
                }
                is Input.Address -> finish(pending, input.address)
            }
        } finally {
            receiver?.close()
            endWaiting(channel)
        }
    }

    private suspend fun finish(pending: Pending, address: String): GoogleSignIn.Result {
        val code = when (val r = oauth.parseRedirect(address, pending.state)) {
            is BrowserOAuth.Redirect.Code -> r.code
            BrowserOAuth.Redirect.WrongState -> return failed(WRONG_ADDRESS, "the address had the wrong state")
            BrowserOAuth.Redirect.Denied -> return failed(DECLINED, "access_denied")
            is BrowserOAuth.Redirect.Error -> return failed("Google didn't sign you in (${r.error}). Try again.", r.error)
        }
        val result = when (val t = oauth.exchange(code, pending.verifier, pending.redirectUri)) {
            is BrowserOAuth.TokenResult.Tokens -> {
                val refreshToken = t.refreshToken
                if (refreshToken == null) {
                    GoogleSignIn.Result.Failed(NO_REFRESH_TOKEN)
                } else {
                    secrets.put(REFRESH_TOKEN_KEY, refreshToken)
                    cache(t, fallbackScopes = allScopes)
                    Log.i(LogTag.Google, "Browser sign-in: signed in")
                    GoogleSignIn.Result.SignedIn(t.email ?: UNKNOWN_EMAIL, null)
                }
            }
            BrowserOAuth.TokenResult.InvalidGrant -> GoogleSignIn.Result.Failed(CODE_EXPIRED)
            BrowserOAuth.TokenResult.InvalidClient -> GoogleSignIn.Result.Failed(BAD_CLIENT)
            BrowserOAuth.TokenResult.NoNetwork -> GoogleSignIn.Result.Failed(NO_NETWORK)
            is BrowserOAuth.TokenResult.Failed -> GoogleSignIn.Result.Failed("Google refused the sign-in (${t.reason}). Try again.")
        }
        clearPending()
        return result
    }

    private suspend fun failed(message: String, why: String): GoogleSignIn.Result {
        Log.w(LogTag.Google, "Browser sign-in: not signed in ($why)")
        clearPending()
        return GoogleSignIn.Result.Failed(message)
    }

    /**
     * A token for [scopes]: the cached one while it's good for another minute and covers them, else a refresh.
     * One refresh at a time ([refreshLock]): tools asking in the same turn share it.
     */
    override suspend fun authorize(scopes: Set<GoogleScope>): GoogleAuthorizer.Result = refreshLock.withLock {
        val wanted = scopes.map { it.uri }.toSet()
        cached?.let { c ->
            if (c.expiresAt.isAfter(clock.now().plusSeconds(MIN_VALIDITY_SECONDS)) && c.scopes.containsAll(wanted)) {
                return@withLock GoogleAuthorizer.Result.Granted(c.token)
            }
        }
        val refreshToken = secrets.get(REFRESH_TOKEN_KEY) ?: return@withLock GoogleAuthorizer.Result.NeedsConsent(null)
        when (val t = oauth.refresh(refreshToken)) {
            is BrowserOAuth.TokenResult.Tokens -> {
                t.refreshToken?.let { secrets.put(REFRESH_TOKEN_KEY, it) }   // Google may rotate it
                val c = cache(t, fallbackScopes = cached?.scopes ?: allScopes)
                if (c.scopes.containsAll(wanted)) {
                    GoogleAuthorizer.Result.Granted(c.token)
                } else {
                    Log.w(LogTag.Google, "Browser sign-in: Google granted fewer scopes than Edith needs")
                    GoogleAuthorizer.Result.NeedsConsent(null)
                }
            }
            BrowserOAuth.TokenResult.InvalidGrant -> {
                // Expired (weekly while the project is in Testing) or revoked: sign in again.
                secrets.put(REFRESH_TOKEN_KEY, null)
                cached = null
                GoogleAuthorizer.Result.NeedsConsent(null)
            }
            BrowserOAuth.TokenResult.InvalidClient -> GoogleAuthorizer.Result.Failed(BAD_CLIENT)
            BrowserOAuth.TokenResult.NoNetwork -> GoogleAuthorizer.Result.Failed(NO_NETWORK)
            is BrowserOAuth.TokenResult.Failed -> GoogleAuthorizer.Result.Failed("Google didn't give access (${t.reason}).")
        }
    }

    /** Never called: this path has no consent intent (Grant access signs in through the browser again). */
    override fun fromConsent(data: Intent?): GoogleAuthorizer.Result = GoogleAuthorizer.Result.Failed("Browser sign-in has no consent screen.")

    override suspend fun clearToken(token: String) {
        if (cached?.token == token) cached = null
    }

    /** Disconnect: revokes the grant at Google (a failure is only logged), then forgets it. */
    override suspend fun revoke(email: String) {
        secrets.get(REFRESH_TOKEN_KEY)?.let { oauth.revoke(it) }
        signOut()
    }

    override suspend fun signOut() {
        secrets.put(REFRESH_TOKEN_KEY, null)
        clearPending()
        cached = null
    }

    private fun cache(t: BrowserOAuth.TokenResult.Tokens, fallbackScopes: Set<String>): Cached =
        Cached(t.accessToken, clock.now().plusSeconds(t.expiresInSeconds), t.scopes ?: fallbackScopes).also { cached = it }

    private suspend fun savePending(p: Pending) = secrets.put(PENDING_KEY, json.encodeToString(Pending.serializer(), p))

    private suspend fun loadPending(): Pending? {
        val stored = secrets.get(PENDING_KEY) ?: return null
        return try {
            json.decodeFromString(Pending.serializer(), stored)
        } catch (e: SerializationException) {
            clearPending()
            null
        } catch (e: IllegalArgumentException) {
            clearPending()
            null
        }
    }

    private suspend fun clearPending() = secrets.put(PENDING_KEY, null)

    companion object {
        const val REFRESH_TOKEN_KEY = "google_refresh_token"
        const val PENDING_KEY = "google_pending_sign_in"
        private const val MIN_VALIDITY_SECONDS = 60L
        private val PENDING_LIFETIME = 10.minutes
        private val allScopes = GoogleScope.entries.map { it.uri }.toSet()
        private val json = Json { ignoreUnknownKeys = true }

        const val NOT_SET_UP = "Browser sign-in isn't set up in this build: the Desktop client ID is missing (docs/google-setup.md)."
        const val NO_LISTENER = "Edith couldn't get ready for Google's answer. Try again."
        const val NO_BROWSER = "There's no web browser on this device to sign in with."
        const val DECLINED = "You didn't allow access, so Google isn't connected."
        const val WRONG_ADDRESS = "That address isn't from this sign-in (it may be from an older one). Sign in again."
        const val TIMED_OUT = "Edith stopped waiting for the browser after a few minutes. Sign in again."
        const val NO_NETWORK = "Couldn't reach Google. Check the connection and try again."
        const val BAD_CLIENT = "Google rejected Edith's browser sign-in: check the Desktop client ID in local.properties (docs/google-setup.md)."
        const val CODE_EXPIRED = "Google says that sign-in expired or was already used. Sign in again."
        const val NO_REFRESH_TOKEN = "Google didn't let Edith stay signed in. Remove Edith at myaccount.google.com/permissions and sign in again."
        private const val UNKNOWN_EMAIL = "your Google account"

        private fun startBrowser(activity: Activity, url: String) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        }
    }
}

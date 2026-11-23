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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import io.ktor.http.Url
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The browser sign-in itself, for every provider (C17b; shared since C23): the provider's page in the real browser,
 * the code back on a [LoopbackReceiver] (or pasted), PKCE and `state`, the code exchange. One sign-in at a time for
 * the whole app, kept in [SecretStore] (`pending_sign_in`) so it survives Edith being killed while the owner is in the
 * browser. What the tokens mean (which account, what to store) is the provider's business ([BrowserGoogle], C23's
 * Microsoft). Nothing secret is logged.
 */
class BrowserSignIn(
    private val secrets: SecretStore,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
    /** `${applicationId}://signed-in`: the loopback page's link back to Edith (C17b §4). */
    private val backLink: String,
    /** Opens [url] in the browser; throws [ActivityNotFoundException] when there's none. */
    private val openBrowser: (Activity, String) -> Unit = DEFAULT_OPEN_BROWSER,
    /** Opens the loopback listener at a port (0: any), for a `localhost` redirect or a `127.0.0.1` one. */
    private val openReceiver: (localhost: Boolean, port: Int) -> LoopbackReceiver = { localhost, port ->
        if (localhost) LoopbackReceiver.openLocalhost(dispatchers.io, backLink, port) else LoopbackReceiver.open(dispatchers.io, backLink, port)
    },
    private val loopbackTimeout: Duration = 5.minutes,
    /**
     * Whether Edith is on screen (null: always, as in JVM tests). Android 17 blocks her network while the browser
     * is in front, so a code that arrives then is exchanged once she's back (C17b As built, L36).
     */
    private val appVisible: StateFlow<Boolean>? = null,
) {
    /** How a sign-in ended. The provider turns a failure into its own words. */
    sealed interface Result {
        data class Tokens(val tokens: BrowserOAuth.TokenResult.Tokens) : Result
        data object Cancelled : Result
        data class Failed(val why: Why) : Result
    }

    sealed interface Why {
        data object NotSetUp : Why
        data object NoListener : Why
        data object NoBrowser : Why
        data object Declined : Why
        data object AdminApproval : Why
        data object WrongAddress : Why
        data object TimedOut : Why
        /** The redirect carried an OAuth error. */
        data class RedirectError(val error: String) : Why
        data object CodeExpired : Why
        data object BadClient : Why
        data object NoNetwork : Why
        /** The token endpoint refused for another reason. */
        data class ExchangeFailed(val reason: String) : Why
    }

    private sealed interface Input {
        data class Address(val address: String) : Input
        data object Cancel : Input
    }

    /** The sign-in in progress. [accountId] is the account being signed in again, null when adding one. */
    @Serializable
    private data class Pending(
        val verifier: String,
        val state: String,
        val redirectUri: String,
        val startedAtMillis: Long,
        val provider: String = "google",
        val accountId: String? = null,
    )

    private val _waiting = MutableStateFlow(false)
    /** True while Edith waits for the browser: the "Finish signing in" dialog shows. */
    val waiting: StateFlow<Boolean> = _waiting.asStateFlow()

    private val _waitingFor = MutableStateFlow<String?>(null)
    /** The provider being signed in to ("google", "microsoft") while [waiting]: the dialog names it. */
    val waitingFor: StateFlow<String?> = _waitingFor.asStateFlow()

    @Volatile private var inputs: Channel<Input>? = null
    private val lock = Mutex()

    /** The waiting dialog's **Use this address**. */
    fun paste(address: String) {
        inputs?.trySend(Input.Address(address))
    }

    /** The waiting dialog's **Cancel**. */
    fun cancel() {
        inputs?.trySend(Input.Cancel)
    }

    /**
     * Opens [oauth]'s sign-in page and waits for the code (loopback or pasted), the owner's cancel, or the timeout,
     * then exchanges it. [loginHint] and [accountId]: signing that account in again; both null: a new account.
     * [scope]: the scopes to ask for (the provider's own when null).
     */
    suspend fun signIn(activity: Activity, oauth: BrowserOAuth, loginHint: String? = null, accountId: String? = null, scope: String? = null): Result {
        if (!oauth.isConfigured) return Result.Failed(Why.NotSetUp)
        if (!lock.tryLock()) return Result.Cancelled   // already waiting: the dialog is up
        try {
            val provider = oauth.provider
            val receiver = try {
                openReceiver(provider.localhostRedirect, 0)
            } catch (e: IOException) {
                Log.w(LogTag.Accounts, "Browser sign-in: couldn't open the loopback listener (${e::class.simpleName})")
                return Result.Failed(Why.NoListener)
            }
            val pkce = oauth.pkce()
            val pending = Pending(pkce.verifier, oauth.newState(), receiver.redirectUri, clock.now().toEpochMilliseconds(), provider.id, accountId)
            savePending(pending)   // survives Edith being killed while the owner is in the browser
            val url = oauth.authUrl(pending.redirectUri, pkce.challenge, pending.state, loginHint, scope ?: provider.scope)
            val channel = beginWaiting(provider.id)   // before the browser opens, so an early paste or cancel isn't lost
            try {
                openBrowser(activity, url)
            } catch (e: ActivityNotFoundException) {
                Log.w(LogTag.Accounts, "Browser sign-in: no browser on this device")
                endWaiting(channel)
                receiver.close()
                clearPending()
                return Result.Failed(Why.NoBrowser)
            }
            Log.i(LogTag.Accounts, "Browser sign-in: waiting for ${provider.displayName} (${accountId ?: "a new account"})")
            return waitFor(oauth, pending, receiver, loopbackTimeout, channel)
        } finally {
            lock.unlock()
        }
    }

    /**
     * A sign-in left pending by an earlier run of Edith (killed while the owner was in the browser): waits for it
     * again, on the same port if it's free, else for a pasted address. [oauthFor] gives the provider's [BrowserOAuth]
     * (null: not in this build, so the sign-in is dropped). Returns the provider, the account it was for (null: a
     * new one) and the result; null when there's none (or it's too old).
     */
    suspend fun resumePending(oauthFor: (provider: String) -> BrowserOAuth?): Resumed? {
        val pending = loadPending() ?: return null
        val age = (clock.now().toEpochMilliseconds() - pending.startedAtMillis).milliseconds
        val oauth = oauthFor(pending.provider)?.takeIf { it.isConfigured }
        if (age < Duration.ZERO || age >= PENDING_LIFETIME || oauth == null) {
            clearPending()
            return null
        }
        if (!lock.tryLock()) return null
        try {
            val port = runCatching { Url(pending.redirectUri) }.getOrNull()?.port
            val receiver = port?.let { p ->
                try { openReceiver(oauth.provider.localhostRedirect, p) } catch (e: IOException) { null }   // taken: the paste still works
            }
            Log.i(LogTag.Accounts, "Browser sign-in: resuming a pending ${pending.provider} sign-in (listening again: ${receiver != null})")
            val result = waitFor(oauth, pending, receiver, PENDING_LIFETIME - age, beginWaiting(pending.provider))
            return Resumed(pending.provider, pending.accountId, result)
        } finally {
            lock.unlock()
        }
    }

    /** What [resumePending] found. */
    data class Resumed(val provider: String, val accountId: String?, val result: Result)

    private fun beginWaiting(provider: String): Channel<Input> = Channel<Input>(Channel.UNLIMITED).also {
        inputs = it
        _waitingFor.value = provider
        _waiting.value = true
    }

    private fun endWaiting(channel: Channel<Input>) {
        inputs = null
        channel.close()
        _waiting.value = false
        _waitingFor.value = null
    }

    private suspend fun waitFor(oauth: BrowserOAuth, pending: Pending, receiver: LoopbackReceiver?, timeout: Duration, channel: Channel<Input>): Result {
        try {
            val input = withTimeoutOrNull(timeout) {
                coroutineScope {
                    val listening = receiver?.let { r -> launch { r.await(timeout)?.let { channel.send(Input.Address(it)) } } }
                    try { channel.receive() } finally { listening?.cancel() }
                }
            }
            return when (input) {
                null -> {
                    Log.w(LogTag.Accounts, "Browser sign-in: timed out")
                    clearPending()
                    Result.Failed(Why.TimedOut)
                }
                Input.Cancel -> {
                    clearPending()
                    Result.Cancelled
                }
                is Input.Address -> finish(oauth, pending, input.address)
            }
        } finally {
            receiver?.close()
            endWaiting(channel)
        }
    }

    private suspend fun finish(oauth: BrowserOAuth, pending: Pending, address: String): Result {
        val code = when (val r = oauth.parseRedirect(address, pending.state)) {
            is BrowserOAuth.Redirect.Code -> r.code
            BrowserOAuth.Redirect.WrongState -> return failed(Why.WrongAddress, "the address had the wrong state")
            BrowserOAuth.Redirect.Denied -> return failed(Why.Declined, "access_denied")
            BrowserOAuth.Redirect.AdminApproval -> return failed(Why.AdminApproval, "admin approval needed")
            is BrowserOAuth.Redirect.Error -> return failed(Why.RedirectError(r.error), r.error)
        }
        appVisible?.let { visible ->
            if (!visible.value) {
                Log.i(LogTag.Accounts, "Browser sign-in: waiting for Edith to be on screen to finish")
                withTimeoutOrNull(loopbackTimeout) { visible.first { it } }
            }
        }
        val result = when (val t = oauth.exchange(code, pending.verifier, pending.redirectUri)) {
            is BrowserOAuth.TokenResult.Tokens -> Result.Tokens(t)
            BrowserOAuth.TokenResult.InvalidGrant -> Result.Failed(Why.CodeExpired)
            BrowserOAuth.TokenResult.InvalidClient -> Result.Failed(Why.BadClient)
            BrowserOAuth.TokenResult.ConsentRequired -> Result.Failed(Why.AdminApproval)
            BrowserOAuth.TokenResult.NoNetwork -> Result.Failed(Why.NoNetwork)
            is BrowserOAuth.TokenResult.Failed -> Result.Failed(Why.ExchangeFailed(t.reason))
        }
        clearPending()
        return result
    }

    private suspend fun failed(why: Why, log: String): Result {
        Log.w(LogTag.Accounts, "Browser sign-in: not signed in ($log)")
        clearPending()
        return Result.Failed(why)
    }

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
        /** C22 §1: one pending sign-in for every provider, with `provider` and `accountId` in its JSON. */
        const val PENDING_KEY = "pending_sign_in"
        val PENDING_LIFETIME = 10.minutes
        private val json = Json { ignoreUnknownKeys = true }

        /** Opens a URL in the real browser. */
        val DEFAULT_OPEN_BROWSER: (Activity, String) -> Unit = { activity, url ->
            activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        }
    }
}

package com.edith.app.google.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import com.edith.app.accounts.AccountSecrets
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.secrets.SecretStore
import com.edith.app.core.time.Clock
import com.edith.app.google.GoogleAuthorizer
import com.edith.app.google.GoogleScope
import com.edith.app.google.GoogleSignIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * What a browser sign-in got from Google (C17b): the refresh token and a first access token. It's handed to
 * [BrowserGoogle.keep] once Edith knows which account it belongs to (C22: the address decides, so adding an account
 * that's already there replaces its token instead of adding it twice).
 */
class BrowserGrant internal constructor(
    internal val refreshToken: String,
    internal val accessToken: String,
    internal val expiresAt: Instant,
    internal val scopes: Set<String>,
)

/**
 * Google sign-in and access through the browser, for devices without Play services (C17b, D16). The sign-in itself
 * runs in the app-wide [BrowserSignIn] (shared with Microsoft since C23). Per account (C22) a refresh token is kept
 * encrypted in [SecretStore] under `account/<id>/refresh_token`; access tokens stay in memory. Nothing secret is logged.
 */
class BrowserGoogle(
    private val oauth: BrowserOAuth,
    private val secrets: SecretStore,
    private val clock: Clock,
    dispatchers: AppDispatchers,
    /** `${applicationId}://signed-in`: the loopback page's link back to Edith (§4). */
    backLink: String,
    /** Opens a URL in the browser; throws [ActivityNotFoundException] when there's none. Null: the real browser. */
    openBrowser: ((Activity, String) -> Unit)? = null,
    /** Opens the loopback listener at a port (0: any). */
    openReceiver: (Int) -> LoopbackReceiver = { LoopbackReceiver.open(dispatchers.io, backLink, it) },
    loopbackTimeout: Duration = 5.minutes,
    /** Whether Edith is on screen (null: always, as in JVM tests); see [BrowserSignIn]. */
    appVisible: StateFlow<Boolean>? = null,
    /** The app-wide sign-in flow, one for every provider. Without one (C17b's tests), Google gets its own. */
    val signInFlow: BrowserSignIn = BrowserSignIn(
        secrets, clock, dispatchers, backLink,
        openBrowser = openBrowser ?: BrowserSignIn.DEFAULT_OPEN_BROWSER,
        openReceiver = { _, port -> openReceiver(port) },
        loopbackTimeout = loopbackTimeout,
        appVisible = appVisible,
    ),
) {
    private val authorizers = ConcurrentHashMap<String, BrowserAuthorizer>()

    val isConfigured: Boolean get() = oauth.isConfigured

    /** True while Edith waits for the browser (any provider's sign-in: one at a time for the whole app). */
    val waiting: StateFlow<Boolean> get() = signInFlow.waiting

    /** Google's [BrowserOAuth], for resuming a pending sign-in. */
    val googleOAuth: BrowserOAuth get() = oauth

    suspend fun hasRefreshToken(accountId: String): Boolean = secrets.get(AccountSecrets.refreshToken(accountId)) != null

    /** Access for one account: its own token cache and its own refresh lock (C22 note 5). */
    fun authorizer(accountId: String): GoogleAuthorizer = accountAuthorizer(accountId)

    private fun accountAuthorizer(accountId: String): BrowserAuthorizer = authorizers.getOrPut(accountId) { BrowserAuthorizer(accountId) }

    /** Stores a sign-in's tokens for [accountId], replacing any it had. */
    suspend fun keep(accountId: String, grant: BrowserGrant) {
        secrets.put(AccountSecrets.refreshToken(accountId), grant.refreshToken)
        accountAuthorizer(accountId).seed(grant)
    }

    /** Forgets [accountId]'s tokens (Disconnect, after revoking). */
    suspend fun forget(accountId: String) {
        secrets.put(AccountSecrets.refreshToken(accountId), null)
        authorizers.remove(accountId)
    }

    /** The waiting dialog's **Use this address**. */
    fun paste(address: String) = signInFlow.paste(address)

    /** The waiting dialog's **Cancel**. */
    fun cancel() = signInFlow.cancel()

    /**
     * Opens Google's sign-in page and waits for the code (loopback or pasted), the owner's cancel, or the timeout.
     * [loginHint] and [accountId]: signing that account in again; both null: a new account, chosen on Google's page.
     * A success carries a [BrowserGrant] for [keep].
     */
    suspend fun signIn(activity: Activity, loginHint: String? = null, accountId: String? = null): GoogleSignIn.Result {
        if (!oauth.isConfigured) return GoogleSignIn.Result.Failed(NOT_SET_UP)
        return result(signInFlow.signIn(activity, oauth, loginHint, accountId, BrowserOAuth.scopeString(GoogleScope.entries.toSet())))
    }

    /**
     * A Google sign-in left pending by an earlier run of Edith: waits for it again. Returns the account it was for
     * (null: a new one) and the result; null when there's none or it's too old. The app itself resumes through
     * [com.edith.app.accounts.Accounts.resumePending], which knows every provider.
     */
    suspend fun resumePending(): Pair<String?, GoogleSignIn.Result>? {
        val r = signInFlow.resumePending { if (it == PROVIDER) oauth else null } ?: return null
        return r.accountId to result(r.result)
    }

    /** The shared flow's answer in Google's words. */
    fun result(r: BrowserSignIn.Result): GoogleSignIn.Result = when (r) {
        BrowserSignIn.Result.Cancelled -> GoogleSignIn.Result.Cancelled
        is BrowserSignIn.Result.Failed -> GoogleSignIn.Result.Failed(message(r.why))
        is BrowserSignIn.Result.Tokens -> {
            val t = r.tokens
            val refreshToken = t.refreshToken
            val email = t.email
            when {
                refreshToken == null -> GoogleSignIn.Result.Failed(NO_REFRESH_TOKEN)
                email == null -> GoogleSignIn.Result.Failed(NO_EMAIL)   // C22: the address decides which account it is
                else -> {
                    Log.i(LogTag.Google, "Browser sign-in: signed in")
                    val grant = BrowserGrant(refreshToken, t.accessToken, (clock.now() + t.expiresInSeconds.seconds), t.scopes ?: allScopes)
                    GoogleSignIn.Result.SignedIn(email, null, grant)
                }
            }
        }
    }

    private fun message(why: BrowserSignIn.Why): String = when (why) {
        BrowserSignIn.Why.NotSetUp -> NOT_SET_UP
        BrowserSignIn.Why.NoListener -> NO_LISTENER
        BrowserSignIn.Why.NoBrowser -> NO_BROWSER
        BrowserSignIn.Why.Declined, BrowserSignIn.Why.AdminApproval -> DECLINED
        BrowserSignIn.Why.WrongAddress -> WRONG_ADDRESS
        BrowserSignIn.Why.TimedOut -> TIMED_OUT
        is BrowserSignIn.Why.RedirectError -> "Google didn't sign you in (${why.error}). Try again."
        BrowserSignIn.Why.CodeExpired -> CODE_EXPIRED
        BrowserSignIn.Why.BadClient -> BAD_CLIENT
        BrowserSignIn.Why.NoNetwork -> NO_NETWORK
        is BrowserSignIn.Why.ExchangeFailed -> "Google refused the sign-in (${why.reason}). Try again."
    }

    /** One account's access through its refresh token. */
    private inner class BrowserAuthorizer(private val accountId: String) : GoogleAuthorizer {
        private inner class Cached(val token: String, val expiresAt: Instant, val scopes: Set<String>)

        @Volatile private var cached: Cached? = null
        private val refreshLock = Mutex()
        private val key = AccountSecrets.refreshToken(accountId)

        fun seed(grant: BrowserGrant) {
            cached = Cached(grant.accessToken, grant.expiresAt, grant.scopes)
        }

        /**
         * A token for [scopes]: the cached one while it's good for another minute and covers them, else a refresh.
         * One refresh at a time per account ([refreshLock]): tools asking in the same turn share it.
         */
        override suspend fun authorize(scopes: Set<GoogleScope>): GoogleAuthorizer.Result = refreshLock.withLock {
            val wanted = scopes.map { it.uri }.toSet()
            cached?.let { c ->
                if (c.expiresAt > clock.now() + MIN_VALIDITY_SECONDS.seconds && c.scopes.containsAll(wanted)) {
                    return@withLock GoogleAuthorizer.Result.Granted(c.token)
                }
            }
            val refreshToken = secrets.get(key) ?: return@withLock GoogleAuthorizer.Result.NeedsConsent(null)
            when (val t = oauth.refresh(refreshToken)) {
                is BrowserOAuth.TokenResult.Tokens -> {
                    t.refreshToken?.let { secrets.put(key, it) }   // Google may rotate it
                    val c = Cached(t.accessToken, (clock.now() + t.expiresInSeconds.seconds), t.scopes ?: cached?.scopes ?: allScopes)
                    cached = c
                    if (c.scopes.containsAll(wanted)) {
                        GoogleAuthorizer.Result.Granted(c.token)
                    } else {
                        Log.w(LogTag.Google, "Browser sign-in: Google granted $accountId fewer scopes than Edith needs")
                        GoogleAuthorizer.Result.NeedsConsent(null)
                    }
                }
                BrowserOAuth.TokenResult.InvalidGrant, BrowserOAuth.TokenResult.ConsentRequired -> {
                    // Expired (weekly while the project is in Testing) or revoked: sign in again.
                    Log.i(LogTag.Google, "Browser sign-in: $accountId needs signing in again")
                    secrets.put(key, null)
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
            secrets.get(key)?.let { oauth.revoke(it) }
            forget(accountId)
        }
    }

    companion object {
        /** C22 §1: one pending sign-in for every provider, with `provider` and `accountId` in its JSON. */
        const val PENDING_KEY = BrowserSignIn.PENDING_KEY
        const val PROVIDER = "google"
        private const val MIN_VALIDITY_SECONDS = 60L
        private val allScopes = GoogleScope.entries.map { it.uri }.toSet()

        const val NOT_SET_UP = "Browser sign-in isn't set up in this build: the Desktop client ID is missing (docs/google-setup.md)."
        const val NO_LISTENER = "Edith couldn't get ready for Google's answer. Try again."
        const val NO_BROWSER = "There's no web browser on this device to sign in with."
        const val DECLINED = "You didn't allow access, so the account isn't connected."
        const val WRONG_ADDRESS = "That address isn't from this sign-in (it may be from an older one). Sign in again."
        const val TIMED_OUT = "Edith stopped waiting for the browser after a few minutes. Sign in again."
        const val NO_NETWORK = "Couldn't reach Google. Check the connection and try again."
        const val BAD_CLIENT = "Google rejected Edith's browser sign-in: check the Desktop client ID in local.properties (docs/google-setup.md)."
        const val CODE_EXPIRED = "Google says that sign-in expired or was already used. Sign in again."
        const val NO_REFRESH_TOKEN = "Google didn't let Edith stay signed in. Remove Edith at myaccount.google.com/permissions and sign in again."
        const val NO_EMAIL = "Google didn't say which account signed in. Sign in again."
    }
}

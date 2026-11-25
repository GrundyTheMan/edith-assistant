package com.edith.app.google.browser

import com.edith.app.accounts.AccountSecrets
import com.edith.app.accounts.PlatformIntent
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.secrets.SecretStore
import com.edith.app.core.time.Clock
import com.edith.app.google.GoogleAuthorizer
import com.edith.app.google.GoogleScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One Google account's access through its refresh token (C17b's `BrowserAuthorizer`, shared for the iPhone in K4.6; Android keeps its own inside
 * `BrowserGoogle`): the refresh token sits encrypted in [SecretStore] under `account/<id>/refresh_token`, access tokens stay in memory, and one refresh
 * runs at a time per account. Nothing secret is logged.
 */
class RefreshTokenAuthorizer(
    accountId: String,
    private val oauth: BrowserOAuth,
    private val secrets: SecretStore,
    private val clock: Clock,
) : GoogleAuthorizer {
    private class Cached(val token: String, val expiresAt: Instant, val scopes: Set<String>)

    @kotlin.concurrent.Volatile private var cached: Cached? = null
    private val refreshLock = Mutex()
    private val key = AccountSecrets.refreshToken(accountId)

    /** After a sign-in: the first access token, so the next call needs no refresh. */
    fun seed(grant: BrowserGrant) {
        cached = Cached(grant.accessToken, grant.expiresAt, grant.scopes)
    }

    /** Whether a refresh token is kept for this account. */
    suspend fun hasRefreshToken(): Boolean = secrets.get(key) != null

    /** Stores a sign-in's tokens, replacing any there were. */
    suspend fun keep(grant: BrowserGrant) {
        secrets.put(key, grant.refreshToken)
        seed(grant)
    }

    /** Forgets the account's tokens on this device. */
    suspend fun forget() {
        secrets.put(key, null)
        cached = null
    }

    /** A token for [scopes]: the cached one while it is good for another minute and covers them, else a refresh. */
    override suspend fun authorize(scopes: Set<GoogleScope>): GoogleAuthorizer.Result = refreshLock.withLock {
        val wanted = scopes.map { it.uri }.toSet()
        cached?.let { c ->
            if (c.expiresAt > clock.now() + MIN_VALIDITY_SECONDS.seconds && c.scopes.containsAll(wanted)) return@withLock GoogleAuthorizer.Result.Granted(c.token)
        }
        val refreshToken = secrets.get(key) ?: return@withLock GoogleAuthorizer.Result.NeedsConsent(null)
        when (val t = oauth.refresh(refreshToken)) {
            is BrowserOAuth.TokenResult.Tokens -> {
                t.refreshToken?.let { secrets.put(key, it) }   // Google may rotate it
                val c = Cached(t.accessToken, clock.now() + t.expiresInSeconds.seconds, t.scopes ?: cached?.scopes ?: ALL_SCOPES)
                cached = c
                if (c.scopes.containsAll(wanted)) {
                    GoogleAuthorizer.Result.Granted(c.token)
                } else {
                    Log.w(LogTag.Google, "Sign-in: Google granted an account fewer scopes than Edith needs")
                    GoogleAuthorizer.Result.NeedsConsent(null)
                }
            }
            BrowserOAuth.TokenResult.InvalidGrant, BrowserOAuth.TokenResult.ConsentRequired -> {
                // Expired (weekly while the project is in Testing) or revoked: sign in again.
                Log.i(LogTag.Google, "Sign-in: an account needs signing in again")
                secrets.put(key, null)
                cached = null
                GoogleAuthorizer.Result.NeedsConsent(null)
            }
            BrowserOAuth.TokenResult.InvalidClient -> GoogleAuthorizer.Result.Failed(BAD_CLIENT)
            BrowserOAuth.TokenResult.NoNetwork -> GoogleAuthorizer.Result.Failed(NO_NETWORK)
            is BrowserOAuth.TokenResult.Failed -> GoogleAuthorizer.Result.Failed("Google didn't give access (${t.reason}).")
        }
    }

    /** Never called: this path has no consent screen (Grant access signs in again). */
    override fun fromConsent(data: PlatformIntent?): GoogleAuthorizer.Result = GoogleAuthorizer.Result.Failed("Browser sign-in has no consent screen.")

    override suspend fun clearToken(token: String) {
        if (cached?.token == token) cached = null
    }

    /** Disconnect: revokes the grant at Google (a failure is only logged), then forgets it. */
    override suspend fun revoke(email: String) {
        secrets.get(key)?.let { oauth.revoke(it) }
        forget()
    }

    companion object {
        private const val MIN_VALIDITY_SECONDS = 60L
        private val ALL_SCOPES = GoogleScope.entries.map { it.uri }.toSet()
        const val NO_NETWORK = "Couldn't reach Google. Check the connection and try again."
        const val BAD_CLIENT = "Google rejected Edith's sign-in: check the iPhone client ID (docs/google-setup.md)."
    }
}

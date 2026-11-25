package com.edith.app.microsoft

import com.edith.app.accounts.PlatformActivity
import com.edith.app.accounts.Account
import com.edith.app.accounts.AccountSecrets
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.sources.AccountErrors
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.secrets.SecretStore
import com.edith.app.core.time.Clock
import com.edith.app.google.GoogleAccountAuth
import com.edith.app.google.browser.BrowserOAuth
import com.edith.app.google.browser.OAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds

/** Microsoft's sign-in (C23 §1): Microsoft Entra's v2.0 endpoints for a public client, no secret. */
object MicrosoftOAuth {
    /** D18 (2026-11-12): personal and work or school accounts. `consumers` would be personal only. */
    const val TENANT = "common"
    const val LOGIN_BASE = "https://login.microsoftonline.com"
    const val SCOPE = "offline_access openid email User.Read Mail.ReadWrite Mail.Send Calendars.ReadWrite"
    const val PROVIDER = "microsoft"

    /**
     * Edith's app registration. A new account gets Microsoft's account picker (`prompt=select_account`); signing one
     * in again suggests its address. The redirect is `http://localhost:<port>` (the port is ignored for localhost), so
     * the listener takes both loopback addresses.
     */
    fun provider(clientId: String, loginBase: String = LOGIN_BASE, tenant: String = TENANT) = OAuthProvider(
        id = PROVIDER,
        displayName = "Microsoft",
        authUrl = "${loginBase.trimEnd('/')}/$tenant/oauth2/v2.0/authorize",
        tokenUrl = "${loginBase.trimEnd('/')}/$tenant/oauth2/v2.0/token",
        revokeUrl = null,   // Microsoft has no endpoint for an app to revoke itself
        clientId = clientId,
        scope = SCOPE,
        authParams = { hint -> if (hint.isNullOrBlank()) listOf("prompt" to "select_account") else listOf("login_hint" to hint) },
        addressClaims = listOf("email", "preferred_username"),
        localhostRedirect = true,
        scopeOnTokenRequests = true,
    )
}

/** What a Microsoft sign-in got: the refresh token and a first access token, kept once the account is known. */
class MicrosoftGrant(
    val refreshToken: String,
    val accessToken: String,
    val expiresAt: Instant,
)

/**
 * One Microsoft account's access (C23 §1, §4): its refresh token, encrypted in [SecretStore] under
 * `account/<id>/refresh_token`, and an access token in memory. Microsoft sends a **new refresh token with every
 * refresh**, which is stored before the access token is used (trap 1). No network keeps the token and shows Try again
 * (C17b trap 3); `invalid_grant` (90 idle days, a changed password, revoked) asks for signing in again.
 */
class MicrosoftAccountAuth(
    private val account: () -> Account,
    private val oauth: BrowserOAuth,
    private val secrets: SecretStore,
    private val clock: Clock,
    /** Signs this account in again through the browser. */
    private val signInAgain: suspend (PlatformActivity) -> Unit,
) : GraphAuth {
    private val _state = MutableStateFlow<AccountState>(AccountState.Checking)
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private class Cached(val token: String, val expiresAt: Instant)

    @kotlin.concurrent.Volatile private var cached: Cached? = null
    private val refreshLock = Mutex()

    private val id: String get() = account().id
    private val key: String get() = AccountSecrets.refreshToken(id)

    /** A sign-in's tokens: the refresh token stored, the access token kept for its lifetime. */
    suspend fun keep(grant: MicrosoftGrant) {
        secrets.put(key, grant.refreshToken)
        cached = Cached(grant.accessToken, grant.expiresAt)
    }

    /** Disconnect: deletes the token on the phone (Microsoft can't be told; the dialog says where to remove Edith). */
    suspend fun forget() {
        secrets.put(key, null)
        cached = null
    }

    /** At startup and after signing in: a quiet refresh says whether access still works. */
    suspend fun check() {
        _state.value = AccountState.Checking
        accessToken()   // right after a sign-in, its fresh access token: no refresh needed
        Log.d(LogTag.Accounts, "Account $id: ${GoogleAccountAuth.stateName(_state.value)}")   // words, not class names (R8)
    }

    /** Sign in again / Try again: a quiet check first (after no network); if Microsoft wants the owner, the browser. */
    suspend fun grant(activity: PlatformActivity) {
        if (_state.value !is AccountState.NeedsSignIn) check()
        if (_state.value is AccountState.NeedsSignIn) signInAgain(activity)
    }

    /**
     * Runs [call] with an access token (C23 §4). A 401 refreshes once and tries again; a second 401, or a 403, means
     * access was withdrawn, so [state] becomes NeedsSignIn.
     */
    override suspend fun <T> authorized(call: suspend (token: String) -> GraphCall<T>): Outcome<T> {
        repeat(2) { attempt ->
            val token = when (val t = accessToken(force = attempt > 0)) {
                is Outcome.Success -> t.value
                is Outcome.Failure -> return t
            }
            when (val r = call(token)) {
                is GraphCall.Ok -> return Outcome.Success(r.value)
                is GraphCall.Failed -> return Outcome.Failure(r.error)
                GraphCall.Forbidden -> {
                    Log.w(LogTag.Accounts, "Account $id: Microsoft says Edith lacks a permission for this call")
                    _state.value = needsSignIn()
                    return Outcome.Failure(AppError.GoogleAuth(AccountErrors.NEEDS_PERMISSION))
                }
                GraphCall.Unauthorized -> {
                    Log.w(LogTag.Accounts, "Account $id: Microsoft refused the token (attempt ${attempt + 1})")
                    cached = null
                }
            }
        }
        _state.value = needsSignIn()
        return Outcome.Failure(AppError.GoogleAuth(AccountErrors.NEEDS_PERMISSION))
    }

    /** A token good for another minute: the cached one, else a refresh (one at a time per account). Never logged. */
    suspend fun accessToken(force: Boolean = false): Outcome<String> = refreshLock.withLock {
        cached?.takeIf { !force && it.expiresAt > clock.now() + MIN_VALIDITY_SECONDS.seconds }?.let {
            return@withLock connected(it.token)
        }
        val refreshToken = secrets.get(key) ?: run {
            _state.value = needsSignIn()
            return@withLock Outcome.Failure(AppError.GoogleAuth(AccountErrors.NEEDS_PERMISSION))
        }
        when (val t = oauth.refresh(refreshToken)) {
            is BrowserOAuth.TokenResult.Tokens -> {
                t.refreshToken?.let { secrets.put(key, it) }   // trap 1: the new one first, before anything else
                cached = Cached(t.accessToken, (clock.now() + t.expiresInSeconds.seconds))
                connected(t.accessToken)
            }
            BrowserOAuth.TokenResult.InvalidGrant, BrowserOAuth.TokenResult.ConsentRequired -> {
                Log.i(LogTag.Accounts, "Account $id: Microsoft needs signing in again")
                secrets.put(key, null)
                cached = null
                _state.value = needsSignIn()
                Outcome.Failure(AppError.GoogleAuth(AccountErrors.NEEDS_PERMISSION))
            }
            BrowserOAuth.TokenResult.InvalidClient -> error(BAD_CLIENT)
            BrowserOAuth.TokenResult.NoNetwork -> error(NO_NETWORK)
            is BrowserOAuth.TokenResult.Failed -> error("Microsoft didn't give access (${t.reason}).")
        }
    }

    private fun connected(token: String): Outcome<String> {
        if (_state.value !is AccountState.Connected) _state.value = AccountState.Connected
        Log.d(LogTag.Accounts, "Account $id: access token: ${token.length} characters")
        return Outcome.Success(token)
    }

    /** No network or a refused client: the token is kept, and Settings shows Try again. */
    private fun error(message: String): Outcome<String> {
        if (_state.value !is AccountState.Connected) _state.value = AccountState.Error(message)
        return Outcome.Failure(AppError.GoogleAuth(message))
    }

    private fun needsSignIn() = AccountState.NeedsSignIn(SIGN_IN_AGAIN, viaBrowser = true)

    companion object {
        private const val MIN_VALIDITY_SECONDS = 60L
        const val SIGN_IN_AGAIN = "needs signing in again"
        const val NO_NETWORK = "Couldn't reach Microsoft. Check the connection and try again."
        const val BAD_CLIENT = "Microsoft rejected Edith's sign-in: check the client ID in local.properties (docs/microsoft-setup.md)."
    }
}

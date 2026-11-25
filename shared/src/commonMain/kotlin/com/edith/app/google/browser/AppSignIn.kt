package com.edith.app.google.browser

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.sync.Mutex

/**
 * Where the browser leaves the owner (K4.4). On the iPhone the system's web-authentication sheet (`ASWebAuthenticationSession`) shows the provider's
 * page and hands the app back the address it ended on, so there is no loopback listener and nothing to paste: [authenticate] opens [url] and waits
 * for an address that starts with [callbackScheme].
 */
fun interface AuthSession {
    suspend fun authenticate(url: String, callbackScheme: String): AuthSessionResult
}

sealed interface AuthSessionResult {
    /** The sheet ended on [url], an address in the callback scheme. */
    data class Callback(val url: String) : AuthSessionResult
    /** The owner closed the sheet. */
    data object Cancelled : AuthSessionResult
    /** The sheet could not be shown (no window to show it in, or the system refused). */
    data class Failed(val reason: String) : AuthSessionResult
}

/**
 * The browser sign-in for an app that gets the provider's answer on its own custom-scheme address (K4.4), the iPhone's counterpart of Android's
 * loopback [BrowserOAuth] flow: PKCE and `state` as there, the code exchange is [BrowserOAuth.exchange]. [redirectUri] is the provider's
 * registered address for the app (Google's iOS client: `com.googleusercontent.apps.<id>:/oauth2redirect`; Microsoft: `msauth.<bundle>://auth`); its
 * scheme is what the sheet watches for. One sign-in at a time. Nothing secret (code, state, verifier, tokens) is logged.
 */
class AppSignIn(
    private val oauth: BrowserOAuth,
    private val session: AuthSession,
    private val redirectUri: String,
) {
    sealed interface Result {
        data class Tokens(val tokens: BrowserOAuth.TokenResult.Tokens) : Result
        data object Cancelled : Result
        data class Failed(val why: Why) : Result
    }

    sealed interface Why {
        data object NotSetUp : Why
        data object Declined : Why
        data object AdminApproval : Why
        data object WrongAddress : Why
        data class RedirectError(val error: String) : Why
        data object CodeExpired : Why
        data object BadClient : Why
        data object NoNetwork : Why
        data class ExchangeFailed(val reason: String) : Why
        /** The sheet could not be shown. */
        data class SheetFailed(val reason: String) : Why
    }

    private val lock = Mutex()
    private val scheme: String = redirectUri.substringBefore(':')

    val provider: OAuthProvider get() = oauth.provider
    val isConfigured: Boolean get() = oauth.isConfigured && scheme.isNotEmpty() && redirectUri.contains(':')

    /**
     * Shows the provider's page and, once the owner has signed in, trades the code for tokens. [loginHint]: signing that account in again; null: a
     * new account (the provider's account chooser). [scope]: the scopes to ask for (the provider's own when null).
     */
    suspend fun signIn(loginHint: String? = null, scope: String? = null): Result {
        if (!isConfigured) return Result.Failed(Why.NotSetUp)
        if (!lock.tryLock()) return Result.Cancelled   // a sheet is already up
        try {
            val pkce = oauth.pkce()
            val state = oauth.newState()
            val url = oauth.authUrl(redirectUri, pkce.challenge, state, loginHint, scope ?: oauth.provider.scope)
            Log.i(LogTag.Accounts, "App sign-in: showing ${oauth.provider.displayName}'s page")
            val address = when (val r = session.authenticate(url, scheme)) {
                is AuthSessionResult.Callback -> r.url
                AuthSessionResult.Cancelled -> return Result.Cancelled
                is AuthSessionResult.Failed -> {
                    Log.w(LogTag.Accounts, "App sign-in: the sheet failed")
                    return Result.Failed(Why.SheetFailed(r.reason))
                }
            }
            val web = asHttp(address) ?: return failed(Why.WrongAddress, "not in the callback scheme")
            val code = when (val r = oauth.parseRedirect(web, state)) {
                is BrowserOAuth.Redirect.Code -> r.code
                BrowserOAuth.Redirect.WrongState -> return failed(Why.WrongAddress, "the address had the wrong state")
                BrowserOAuth.Redirect.Denied -> return failed(Why.Declined, "access_denied")
                BrowserOAuth.Redirect.AdminApproval -> return failed(Why.AdminApproval, "admin approval needed")
                is BrowserOAuth.Redirect.Error -> return failed(Why.RedirectError(r.error), r.error)
            }
            return when (val t = oauth.exchange(code, pkce.verifier, redirectUri)) {
                is BrowserOAuth.TokenResult.Tokens -> Result.Tokens(t)
                BrowserOAuth.TokenResult.InvalidGrant -> Result.Failed(Why.CodeExpired)
                BrowserOAuth.TokenResult.InvalidClient -> Result.Failed(Why.BadClient)
                BrowserOAuth.TokenResult.ConsentRequired -> Result.Failed(Why.AdminApproval)
                BrowserOAuth.TokenResult.NoNetwork -> Result.Failed(Why.NoNetwork)
                is BrowserOAuth.TokenResult.Failed -> Result.Failed(Why.ExchangeFailed(t.reason))
            }
        } finally {
            lock.unlock()
        }
    }

    private fun failed(why: Why, log: String): Result {
        Log.w(LogTag.Accounts, "App sign-in: not signed in ($log)")
        return Result.Failed(why)
    }

    /**
     * The callback address as an `http://` one that [BrowserOAuth.parseRedirect] reads (it only knows web addresses): `scheme:/path?query` and
     * `scheme://host/path?query` both work. Null when [address] isn't in the callback scheme.
     */
    internal fun asHttp(address: String): String? {
        val trimmed = address.trim()
        if (!trimmed.startsWith("$scheme:", ignoreCase = true)) return null
        val rest = trimmed.substring(scheme.length + 1)
        return if (rest.startsWith("//")) "http:$rest" else "http://callback$rest"
    }
}

package com.edith.app.google.browser

/**
 * One provider's browser sign-in (C23 §1, generalised from C17b): its endpoints, Edith's client, the scopes she asks
 * for, the extra sign-in parameters, and where the id_token says who signed in. [BrowserOAuth] does the rest (PKCE,
 * `state`, the code exchange and refresh) the same way for every provider.
 */
data class OAuthProvider(
    /** "google" or "microsoft": the pending sign-in's `provider` (C22 §1) and the logs. */
    val id: String,
    /** "Google", "Microsoft": in messages to the owner. */
    val displayName: String,
    val authUrl: String,
    val tokenUrl: String,
    /** Null: the provider has no endpoint for an app to revoke itself (Microsoft). */
    val revokeUrl: String?,
    val clientId: String,
    /** Empty for a public client (Microsoft). Google's Desktop client has one, which isn't really secret (D16). */
    val clientSecret: String = "",
    /** The space-separated scopes asked for when [BrowserOAuth.authUrl] isn't given others. */
    val scope: String,
    /** Extra sign-in page parameters: for a new account ([loginHint] null) and for signing one in again. */
    val authParams: (loginHint: String?) -> List<Pair<String, String>>,
    /** The id_token claims that hold the account's address, in order of preference. */
    val addressClaims: List<String> = listOf("email"),
    /** Whether the redirect is `http://localhost:<port>` (both loopback addresses) rather than `http://127.0.0.1:<port>`. */
    val localhostRedirect: Boolean = false,
    /** Microsoft: `scope` is sent with the code exchange and every refresh too. */
    val scopeOnTokenRequests: Boolean = false,
) {
    val isConfigured: Boolean get() = clientId.isNotBlank()

    override fun toString(): String = "OAuthProvider($id)"   // never the client ID or secret

    companion object {
        const val GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val GOOGLE_REVOKE_URL = "https://oauth2.googleapis.com/revoke"

        /**
         * Google's Desktop client (C17b, D16). With a login hint it signs that account in again; without one it's a
         * new account (C22 §2): `prompt=select_account consent` shows Google's account chooser.
         */
        fun google(
            clientId: String,
            clientSecret: String = "",
            authUrl: String = GOOGLE_AUTH_URL,
            tokenUrl: String = GOOGLE_TOKEN_URL,
            revokeUrl: String = GOOGLE_REVOKE_URL,
            scope: String = "openid email",
        ) = OAuthProvider(
            id = "google",
            displayName = "Google",
            authUrl = authUrl,
            tokenUrl = tokenUrl,
            revokeUrl = revokeUrl,
            clientId = clientId,
            clientSecret = clientSecret,
            scope = scope,
            authParams = { hint ->
                buildList {
                    add("access_type" to "offline")
                    add("prompt" to if (hint.isNullOrBlank()) "select_account consent" else "consent")
                    if (!hint.isNullOrBlank()) add("login_hint" to hint)
                }
            },
        )
    }
}

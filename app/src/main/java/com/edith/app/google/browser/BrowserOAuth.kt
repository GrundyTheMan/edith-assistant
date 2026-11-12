package com.edith.app.google.browser

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.google.GoogleScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Google's OAuth endpoints for a Desktop client (C17b §1, D16): the authorization code with PKCE, a refresh token
 * and revocation. Pure logic over OkHttp, so it runs on the JVM against MockWebServer. Nothing it handles (code,
 * state, verifier, tokens, id_token) is ever logged: only the step and the HTTP status or OAuth `error` code.
 */
class BrowserOAuth(
    private val http: Lazy<OkHttpClient>,
    private val dispatchers: AppDispatchers,
    private val clientId: String,
    private val clientSecret: String = "",
    private val authBaseUrl: String = AUTH_URL,
    private val tokenUrl: String = TOKEN_URL,
    private val revokeUrl: String = REVOKE_URL,
    private val random: SecureRandom = SecureRandom(),
) {
    /** A PKCE pair (RFC 7636): the [verifier] stays on the device, the [challenge] goes in the sign-in address. */
    data class Pkce(val verifier: String, val challenge: String)

    /** What the token endpoint said. */
    sealed interface TokenResult {
        data class Tokens(
            val accessToken: String,
            val expiresInSeconds: Long,
            /** Always returned for a code exchange; usually absent for a refresh. */
            val refreshToken: String?,
            /** The scopes Google actually granted (the owner can untick some on the consent screen); null if not said. */
            val scopes: Set<String>?,
            /** From the id_token (only after a code exchange). */
            val email: String?,
        ) : TokenResult
        /** `invalid_grant`: the code or refresh token is expired, used or revoked. Sign in again. */
        data object InvalidGrant : TokenResult
        /** Google doesn't accept this client (wrong ID, or a secret it needs is missing). */
        data object InvalidClient : TokenResult
        /** No answer from Google (no network). */
        data object NoNetwork : TokenResult
        data class Failed(val reason: String) : TokenResult
    }

    /** What a redirect address (from the loopback, or pasted) carried. */
    sealed interface Redirect {
        data class Code(val code: String) : Redirect
        /** The owner declined (`error=access_denied`). */
        data object Denied : Redirect
        data class Error(val error: String) : Redirect
        /** Not from this sign-in (another or an older attempt, or not a sign-in address at all). Never accepted. */
        data object WrongState : Redirect
    }

    val isConfigured: Boolean get() = clientId.isNotBlank()

    /** A random 64-character verifier and its S256 challenge (base64url without padding). */
    fun pkce(): Pkce {
        val verifier = randomToken(48)   // 48 bytes → 64 base64url characters, all from the allowed set
        return Pkce(verifier, challengeFor(verifier))
    }

    /** A random, unguessable `state` for one sign-in. */
    fun newState(): String = randomToken(24)

    /** Google's sign-in page for [scopes] (plus `openid email`, for the id_token's email). */
    fun authUrl(redirect: String, challenge: String, state: String, scopes: Set<GoogleScope>, loginHint: String? = null): String =
        authBaseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirect)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", scopeString(scopes))
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .addQueryParameter("access_type", "offline")
            .addQueryParameter("prompt", "consent")
            .apply { if (!loginHint.isNullOrBlank()) addQueryParameter("login_hint", loginHint) }
            .build()
            .toString()

    /** Trades the authorization [code] for tokens. [redirect] must be exactly the one in [authUrl]. */
    suspend fun exchange(code: String, verifier: String, redirect: String): TokenResult = postToken("exchange") {
        add("grant_type", "authorization_code")
        add("code", code)
        add("code_verifier", verifier)
        add("redirect_uri", redirect)
    }

    /** A new access token from the stored [refreshToken]. */
    suspend fun refresh(refreshToken: String): TokenResult = postToken("refresh") {
        add("grant_type", "refresh_token")
        add("refresh_token", refreshToken)
    }

    /** Revokes [token] (and with it every token of the grant). False if Google couldn't be told. */
    suspend fun revoke(token: String): Boolean = withContext(dispatchers.io) {
        val request = Request.Builder().url(revokeUrl).post(FormBody.Builder().add("token", token).build()).build()
        try {
            http.value.newCall(request).execute().use { response ->
                if (!response.isSuccessful) Log.w(LogTag.Google, "Browser sign-in: revoke answered HTTP ${response.code}")
                response.isSuccessful
            }
        } catch (e: IOException) {
            Log.w(LogTag.Google, "Browser sign-in: revoke didn't reach Google (${e::class.simpleName})")
            false
        }
    }

    /**
     * Reads a redirect address, which may have been pasted: surrounding spaces and a missing `http://` are fine.
     * Only an address carrying [expectedState] is accepted.
     */
    fun parseRedirect(address: String, expectedState: String): Redirect {
        val trimmed = address.trim()
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return Redirect.WrongState
        val state = url.queryParameter("state")
        if (state == null || !MessageDigest.isEqual(state.toByteArray(), expectedState.toByteArray())) return Redirect.WrongState
        url.queryParameter("error")?.let { return if (it == "access_denied") Redirect.Denied else Redirect.Error(it) }
        val code = url.queryParameter("code")
        return if (code.isNullOrBlank()) Redirect.Error("no_code") else Redirect.Code(code)
    }

    private suspend fun postToken(step: String, fields: FormBody.Builder.() -> Unit): TokenResult = withContext(dispatchers.io) {
        val form = FormBody.Builder().apply {
            fields()
            add("client_id", clientId)
            if (clientSecret.isNotBlank()) add("client_secret", clientSecret)
        }.build()
        val request = Request.Builder().url(tokenUrl).post(form).build()
        try {
            http.value.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) {
                    parseTokens(body) ?: TokenResult.Failed("unreadable answer").also {
                        Log.w(LogTag.Google, "Browser sign-in: $step answer couldn't be read (HTTP ${response.code})")
                    }
                } else {
                    val error = parseError(body)
                    Log.w(LogTag.Google, "Browser sign-in: $step refused, HTTP ${response.code}, error ${error?.error ?: "none"}")
                    when {
                        error?.error == "invalid_grant" -> TokenResult.InvalidGrant
                        error?.error == "invalid_client" || error?.error == "unauthorized_client" -> TokenResult.InvalidClient
                        // A Desktop client whose secret Google still wants says so in the description.
                        error?.description?.contains("client_secret") == true -> TokenResult.InvalidClient
                        else -> TokenResult.Failed(error?.error ?: "HTTP ${response.code}")
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(LogTag.Google, "Browser sign-in: $step didn't reach Google (${e::class.simpleName})")
            TokenResult.NoNetwork
        }
    }

    private fun parseTokens(body: String): TokenResult.Tokens? = try {
        val dto = json.decodeFromString<TokenDto>(body)
        TokenResult.Tokens(
            accessToken = dto.accessToken,
            expiresInSeconds = dto.expiresIn,
            refreshToken = dto.refreshToken,
            scopes = dto.scope?.split(' ')?.filter { it.isNotBlank() }?.toSet(),
            email = dto.idToken?.let(::emailFromIdToken),
        )
    } catch (e: SerializationException) {
        null   // its message would quote the body, which holds tokens (L32): not logged
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun parseError(body: String): ErrorDto? = try {
        json.decodeFromString<ErrorDto>(body)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    @Serializable
    private data class TokenDto(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long = 3600,
        @SerialName("refresh_token") val refreshToken: String? = null,
        val scope: String? = null,
        @SerialName("id_token") val idToken: String? = null,
    )

    @Serializable
    private data class ErrorDto(val error: String? = null, @SerialName("error_description") val description: String? = null)

    @Serializable
    private data class IdTokenPayload(val email: String? = null)

    private fun randomToken(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    companion object {
        const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"

        private val json = Json { ignoreUnknownKeys = true }

        /** The S256 challenge for [verifier]: base64url(SHA-256(verifier)) without padding (RFC 7636 §4.2). */
        fun challengeFor(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        /** `openid email` plus every scope Edith uses. */
        fun scopeString(scopes: Set<GoogleScope>): String = (listOf("openid", "email") + scopes.map { it.uri }).joinToString(" ")

        /**
         * The email in an id_token's payload. The signature isn't checked: the token came straight from Google's
         * token endpoint over TLS, which OpenID Connect Core §3.1.3.7 accepts in place of validating it.
         */
        internal fun emailFromIdToken(idToken: String): String? {
            val payload = idToken.split('.').getOrNull(1) ?: return null
            return try {
                json.decodeFromString<IdTokenPayload>(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)).email
            } catch (e: IllegalArgumentException) {
                null
            } catch (e: SerializationException) {
                null
            }
        }
    }
}

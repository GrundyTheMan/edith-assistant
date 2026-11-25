@file:OptIn(ExperimentalEncodingApi::class)

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ParametersBuilder
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import com.edith.app.core.util.RandomSource
import com.edith.app.core.util.Sha256
import com.edith.app.core.util.constantTimeEquals
import com.edith.app.core.util.systemRandom
import kotlinx.io.IOException

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A provider's OAuth endpoints for an installed app (C17b §1, D16; any [OAuthProvider] since C23): the authorization
 * code with PKCE, a refresh token and, where the provider has one, revocation. Pure logic over Ktor, so it runs on
 * the JVM against MockWebServer. Nothing it handles (code, state, verifier, tokens, id_token) is ever logged: only the
 * step and the HTTP status or OAuth `error` code.
 */
class BrowserOAuth(
    private val http: Lazy<HttpClient>,
    private val dispatchers: AppDispatchers,
    val provider: OAuthProvider,
    private val random: RandomSource = systemRandom(),
) {
    /** Google's Desktop client (C17b): the constructor C17b's code and tests use. */
    constructor(
        http: Lazy<HttpClient>,
        dispatchers: AppDispatchers,
        clientId: String,
        clientSecret: String = "",
        authBaseUrl: String = AUTH_URL,
        tokenUrl: String = TOKEN_URL,
        revokeUrl: String = REVOKE_URL,
        random: RandomSource = systemRandom(),
    ) : this(http, dispatchers, OAuthProvider.google(clientId, clientSecret, authBaseUrl, tokenUrl, revokeUrl), random)

    /** A PKCE pair (RFC 7636): the [verifier] stays on the device, the [challenge] goes in the sign-in address. */
    data class Pkce(val verifier: String, val challenge: String)

    /** What the token endpoint said. */
    sealed interface TokenResult {
        data class Tokens(
            val accessToken: String,
            val expiresInSeconds: Long,
            /** Google: always for a code exchange, usually absent for a refresh. Microsoft: a new one every time. */
            val refreshToken: String?,
            /** The scopes actually granted (the owner can untick some on Google's consent screen); null if not said. */
            val scopes: Set<String>?,
            /** The account's address from the id_token (only after a code exchange). */
            val email: String?,
        ) : TokenResult
        /** `invalid_grant`: the code or refresh token is expired, used or revoked. Sign in again. */
        data object InvalidGrant : TokenResult
        /** The provider doesn't accept this client (wrong ID, or a secret it needs is missing). */
        data object InvalidClient : TokenResult
        /** Microsoft's AADSTS65001: nobody has approved Edith for this account (a work account's admin must). */
        data object ConsentRequired : TokenResult
        /** No answer (no network). */
        data object NoNetwork : TokenResult
        data class Failed(val reason: String) : TokenResult
    }

    /** What a redirect address (from the loopback, or pasted) carried. */
    sealed interface Redirect {
        data class Code(val code: String) : Redirect
        /** The owner declined (`error=access_denied`). */
        data object Denied : Redirect
        /** Microsoft: the account's organisation lets only its IT admin approve apps (AADSTS65001, 90094, 90095). */
        data object AdminApproval : Redirect
        data class Error(val error: String) : Redirect
        /** Not from this sign-in (another or an older attempt, or not a sign-in address at all). Never accepted. */
        data object WrongState : Redirect
    }

    val isConfigured: Boolean get() = provider.isConfigured

    /** A random 64-character verifier and its S256 challenge (base64url without padding). */
    fun pkce(): Pkce {
        val verifier = randomToken(48)   // 48 bytes → 64 base64url characters, all from the allowed set
        return Pkce(verifier, challengeFor(verifier))
    }

    /** A random, unguessable `state` for one sign-in. */
    fun newState(): String = randomToken(24)

    /** Google's sign-in page for [scopes] (plus `openid email`, for the id_token's email): C17b's form. */
    fun authUrl(redirect: String, challenge: String, state: String, scopes: Set<GoogleScope>, loginHint: String? = null): String =
        authUrl(redirect, challenge, state, loginHint, scopeString(scopes))

    /**
     * The provider's sign-in page. With [loginHint] it signs that account in again; without one it's a new account
     * (the provider's account chooser).
     */
    fun authUrl(redirect: String, challenge: String, state: String, loginHint: String?, scope: String = provider.scope): String =
        URLBuilder(provider.authUrl).apply {
            parameters.append("client_id", provider.clientId)
            parameters.append("redirect_uri", redirect)
            parameters.append("response_type", "code")
            parameters.append("scope", scope)
            parameters.append("code_challenge", challenge)
            parameters.append("code_challenge_method", "S256")
            parameters.append("state", state)
            provider.authParams(loginHint).forEach { (k, v) -> parameters.append(k, v) }
        }.buildString()

    /** Trades the authorization [code] for tokens. [redirect] must be exactly the one in [authUrl]. */
    suspend fun exchange(code: String, verifier: String, redirect: String): TokenResult = postToken("exchange") {
        append("grant_type", "authorization_code")
        append("code", code)
        append("code_verifier", verifier)
        append("redirect_uri", redirect)
    }

    /** A new access token from the stored [refreshToken]. */
    suspend fun refresh(refreshToken: String): TokenResult = postToken("refresh") {
        append("grant_type", "refresh_token")
        append("refresh_token", refreshToken)
    }

    /** Revokes [token] (and with it every token of the grant). False if the provider couldn't be told, or has no way. */
    suspend fun revoke(token: String): Boolean = withContext(dispatchers.io) {
        val url = provider.revokeUrl ?: return@withContext false
        try {
            val response = http.value.submitForm(url, formParameters = parameters { append("token", token) })
            if (!response.status.isSuccess()) Log.w(tag, "Browser sign-in: revoke answered HTTP ${response.status.value}")
            response.status.isSuccess()
        } catch (e: IOException) {
            Log.w(tag, "Browser sign-in: revoke didn't reach ${provider.displayName} (${e::class.simpleName})")
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
        val url = runCatching { Url(withScheme) }.getOrNull()?.takeIf { (it.protocol == URLProtocol.HTTP || it.protocol == URLProtocol.HTTPS) && it.host.isNotEmpty() } ?: return Redirect.WrongState
        val state = url.parameters["state"]
        if (state == null || !constantTimeEquals(state.encodeToByteArray(), expectedState.encodeToByteArray())) return Redirect.WrongState
        url.parameters["error"]?.let { error ->
            if (needsAdmin(url.parameters["error_description"])) return Redirect.AdminApproval
            return if (error == "access_denied") Redirect.Denied else Redirect.Error(error)
        }
        val code = url.parameters["code"]
        return if (code.isNullOrBlank()) Redirect.Error("no_code") else Redirect.Code(code)
    }

    private val tag: LogTag get() = if (provider.id == "google") LogTag.Google else LogTag.Accounts

    private suspend fun postToken(step: String, fields: ParametersBuilder.() -> Unit): TokenResult = withContext(dispatchers.io) {
        val form = parameters {
            fields()
            append("client_id", provider.clientId)
            if (provider.scopeOnTokenRequests) append("scope", provider.scope)
            if (provider.clientSecret.isNotBlank()) append("client_secret", provider.clientSecret)
        }
        try {
            val response = http.value.submitForm(provider.tokenUrl, formParameters = form)
            val body = response.bodyAsText()
            val code = response.status.value
            if (response.status.isSuccess()) {
                parseTokens(body) ?: TokenResult.Failed("unreadable answer").also {
                    Log.w(tag, "Browser sign-in: $step answer couldn't be read (HTTP $code)")
                }
            } else {
                val error = parseError(body)
                val aadsts = error?.description?.let { AADSTS.find(it)?.value }
                Log.w(tag, "Browser sign-in: $step refused, HTTP $code, error ${error?.error ?: "none"}${aadsts?.let { " ($it)" }.orEmpty()}")
                when {
                    aadsts == "AADSTS65001" -> TokenResult.ConsentRequired
                    error?.error == "invalid_grant" -> TokenResult.InvalidGrant
                    error?.error == "invalid_client" || error?.error == "unauthorized_client" -> TokenResult.InvalidClient
                    // A Desktop client whose secret Google still wants says so in the description.
                    error?.description?.contains("client_secret") == true -> TokenResult.InvalidClient
                    else -> TokenResult.Failed(error?.error ?: "HTTP $code")
                }
            }
        } catch (e: IOException) {
            Log.w(tag, "Browser sign-in: $step didn't reach ${provider.displayName} (${e::class.simpleName})")
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
            email = dto.idToken?.let { addressFromIdToken(it, provider.addressClaims) },
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

    private fun randomToken(bytes: Int): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(ByteArray(bytes).also(random::nextBytes))

    companion object {
        const val AUTH_URL = OAuthProvider.GOOGLE_AUTH_URL
        const val TOKEN_URL = OAuthProvider.GOOGLE_TOKEN_URL
        const val REVOKE_URL = OAuthProvider.GOOGLE_REVOKE_URL

        private val json = Json { ignoreUnknownKeys = true }
        private val AADSTS = Regex("AADSTS\\d+")
        /** Microsoft's "an admin has to approve this app" codes. */
        private val ADMIN_CODES = setOf("AADSTS65001", "AADSTS90094", "AADSTS90095")

        fun needsAdmin(description: String?): Boolean =
            description != null && AADSTS.findAll(description).any { it.value in ADMIN_CODES }

        /** The S256 challenge for [verifier]: base64url(SHA-256(verifier)) without padding (RFC 7636 §4.2). */
        fun challengeFor(verifier: String): String =
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(Sha256.digest(verifier.encodeToByteArray()))

        /** `openid email` plus every scope Edith uses. */
        fun scopeString(scopes: Set<GoogleScope>): String = (listOf("openid", "email") + scopes.map { it.uri }).joinToString(" ")

        /** The email in an id_token's payload (Google's claim). */
        fun emailFromIdToken(idToken: String): String? = addressFromIdToken(idToken, listOf("email"))

        /**
         * The first non-blank of [claims] in an id_token's payload. The signature isn't checked: the token came straight
         * from the provider's token endpoint over TLS, which OpenID Connect Core §3.1.3.7 accepts in place of validating it.
         */
        fun addressFromIdToken(idToken: String, claims: List<String>): String? {
            val payload = idToken.split('.').getOrNull(1) ?: return null
            return try {
                val obj: JsonObject = json.parseToJsonElement(Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(payload).decodeToString()).jsonObject
                claims.firstNotNullOfOrNull { c -> (obj[c] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() } }
            } catch (e: IllegalArgumentException) {
                null
            } catch (e: SerializationException) {
                null
            }
        }
    }
}

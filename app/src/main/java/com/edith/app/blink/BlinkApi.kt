package com.edith.app.blink

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Where Blink's services live. Tests point all of them at one pretend server. */
data class BlinkEndpoints(
    val oauth: String = "https://api.oauth.blink.com",
    /** Where the tier (region) is asked, before the account's own server is known. */
    val tierInfo: String = "https://rest-prod.immedia-semi.com/api/v1/users/tier_info",
    /** The account's own server, by tier ("u014"). */
    val rest: (tier: String) -> String = { "https://rest-$it.immedia-semi.com" },
)

/** The tokens Blink hands out. The refresh token may change at each refresh: keep the newest. */
data class BlinkTokens(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long)

/** Which account and server the tokens belong to. */
data class BlinkTier(val tier: String, val accountId: String)

/**
 * A sign-in that has started: Blink's page was loaded, its cookies and CSRF token are held here, and the PKCE verifier
 * that the token exchange needs. It lives in memory only, until a code is typed or the sign-in ends.
 */
class BlinkSignInSession internal constructor(internal val client: OkHttpClient, internal val csrf: String, internal val verifier: String, val hardwareId: String)

sealed interface BlinkStart {
    data class Ok(val session: BlinkSignInSession) : BlinkStart
    data class Failed(val why: String) : BlinkStart
}

/** How a sign-in step ended (C29). */
sealed interface BlinkLogin {
    data class Ok(val tokens: BlinkTokens) : BlinkLogin
    /** Blink wants the code it sent (a text, email or authenticator). */
    data object NeedsCode : BlinkLogin
    data object WrongCode : BlinkLogin
    data object BadLogin : BlinkLogin
    data class Failed(val why: String) : BlinkLogin
}

sealed interface BlinkRefresh {
    data class Ok(val tokens: BlinkTokens) : BlinkRefresh
    /** Blink no longer accepts the refresh token: only signing in again helps. */
    data object Rejected : BlinkRefresh
    data class Failed(val why: String) : BlinkRefresh
}

/** The answer to one call with an access token. */
sealed interface BlinkReply<out T> {
    data class Ok<T>(val value: T) : BlinkReply<T>
    /** HTTP 401: the access token is no good (expired): refresh and ask again. */
    data object Unauthorized : BlinkReply<Nothing>
    /** HTTP 409: the sync module is busy with an earlier command; asking again in a few seconds usually works. */
    data class Busy(val why: String) : BlinkReply<Nothing>
    data class Failed(val why: String) : BlinkReply<Nothing>
}

/**
 * Blink's own (unofficial) service, as the maintained `blinkpy` uses it (read from its `dev` branch on 2026-11-15): a
 * browser-style sign-in against `api.oauth.blink.com` (the sign-in page's CSRF token, the password, a 2FA code, then an
 * authorization code traded for tokens with PKCE), the account's tier, the homescreen, snapshots by command, and the
 * media list. Stateless apart from [BlinkSignInSession]: [BlinkService] keeps the tokens. Passwords, codes, tokens and
 * answers are never logged.
 */
class BlinkApi(
    private val http: Lazy<OkHttpClient>,
    private val endpoints: BlinkEndpoints = BlinkEndpoints(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ---- sign-in ----

    /** Loads Blink's sign-in page the way its phone app does, to get its cookies and CSRF token. */
    suspend fun begin(hardwareId: String): BlinkStart {
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val client = http.value.newBuilder().cookieJar(MemoryCookies()).callTimeout(CALL_S, TimeUnit.SECONDS).build()
        val authorize = "${endpoints.oauth}/oauth/v2/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("app_brand", "blink").addQueryParameter("app_version", APP_VERSION).addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("code_challenge", challenge).addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("device_brand", "Apple").addQueryParameter("device_model", "iPhone16,1").addQueryParameter("device_os_version", "26.1")
            .addQueryParameter("hardware_id", hardwareId).addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("response_type", "code").addQueryParameter("scope", "client").build()
        val first = send(client, Request.Builder().url(authorize).header("User-Agent", PAGE_AGENT).header("Accept", PAGE_ACCEPT).get().build())
        if (first !is Raw.Done) return BlinkStart.Failed((first as Raw.Failed).why)
        if (first.code != 200) return BlinkStart.Failed("Blink's sign-in page answered HTTP ${first.code}")
        val page = send(client, Request.Builder().url("${endpoints.oauth}/oauth/v2/signin").header("User-Agent", PAGE_AGENT).header("Accept", PAGE_ACCEPT).get().build())
        if (page !is Raw.Done) return BlinkStart.Failed((page as Raw.Failed).why)
        if (page.code != 200) return BlinkStart.Failed("Blink's sign-in page answered HTTP ${page.code}")
        val csrf = BlinkParse.csrfToken(page.text) ?: return BlinkStart.Failed("Blink's sign-in page wasn't what Edith expected (Blink may have changed it)")
        return BlinkStart.Ok(BlinkSignInSession(client, csrf, verifier, hardwareId))
    }

    /** The password step. Success or a code to type next; on success the tokens come back (the code is traded for them). */
    suspend fun signIn(session: BlinkSignInSession, email: String, password: String): BlinkLogin {
        val form = FormBody.Builder().add("username", email).add("password", password).add("csrf-token", session.csrf).build()
        val r = send(noRedirects(session), Request.Builder().url("${endpoints.oauth}/oauth/v2/signin").headers(formHeaders()).post(form).build())
        if (r !is Raw.Done) return BlinkLogin.Failed((r as Raw.Failed).why)
        if (r.code !in REDIRECTS && r.code != 412) Log.w(LogTag.Home, "Blink: the password step answered HTTP ${r.code}")
        return when {
            r.code == 412 -> BlinkLogin.NeedsCode
            // 202 with the code methods listed is the same thing.
            r.code == 202 && wantsCode(r.text) -> BlinkLogin.NeedsCode
            r.code in REDIRECTS -> finish(session)
            r.code == 400 || r.code == 401 || r.code == 403 || r.code == 200 -> BlinkLogin.BadLogin
            r.code == 429 -> BlinkLogin.Failed("Blink says to slow down; try again in a few minutes")
            else -> BlinkLogin.Failed("Blink's sign-in answered HTTP ${r.code}")
        }
    }

    /** The code step: [code] is what Blink sent. */
    suspend fun verify(session: BlinkSignInSession, code: String): BlinkLogin {
        val form = FormBody.Builder().add("2fa_code", code).add("csrf-token", session.csrf).add("remember_me", "false").build()
        val r = send(noRedirects(session), Request.Builder().url("${endpoints.oauth}/oauth/v2/2fa/verify").headers(formHeaders()).post(form).build())
        if (r !is Raw.Done) return BlinkLogin.Failed((r as Raw.Failed).why)
        if (r.code != 201) Log.w(LogTag.Home, "Blink: the code step answered HTTP ${r.code}")
        val ok = r.code == 201 && (runCatching { (json.parseToJsonElement(r.text) as? JsonObject)?.get("status") as? JsonPrimitive }.getOrNull()?.contentOrNull == "auth-completed")
        return when {
            ok -> finish(session)
            r.code in 400..499 && r.code != 429 -> BlinkLogin.WrongCode
            r.code == 429 -> BlinkLogin.Failed("Blink says to slow down; try again in a few minutes")
            else -> BlinkLogin.Failed("Blink's code check answered HTTP ${r.code}")
        }
    }

    /** After the password (and code): Blink sends the sign-in to its app's address with a one-time code, which is traded for tokens. */
    private suspend fun finish(session: BlinkSignInSession): BlinkLogin {
        val authorize = send(noRedirects(session), Request.Builder().url("${endpoints.oauth}/oauth/v2/authorize")
            .header("User-Agent", PAGE_AGENT).header("Accept", "*/*").header("Referer", "${endpoints.oauth}/oauth/v2/signin").get().build())
        if (authorize !is Raw.Done) return BlinkLogin.Failed((authorize as Raw.Failed).why)
        if (authorize.code !in REDIRECTS) Log.w(LogTag.Home, "Blink: the final authorize answered HTTP ${authorize.code}, not a redirect")
        val code = (if (authorize.code in REDIRECTS) BlinkParse.codeFrom(authorize.location) else null)
            ?: return BlinkLogin.Failed("Blink finished the sign-in without a code")
        val form = FormBody.Builder().add("app_brand", "blink").add("client_id", CLIENT_ID).add("code", code).add("code_verifier", session.verifier)
            .add("grant_type", "authorization_code").add("hardware_id", session.hardwareId).add("redirect_uri", REDIRECT_URI).add("scope", "client").build()
        return when (val t = token(form)) {
            is BlinkRefresh.Ok -> BlinkLogin.Ok(t.tokens)
            BlinkRefresh.Rejected -> BlinkLogin.Failed("Blink wouldn't trade the sign-in for a session")
            is BlinkRefresh.Failed -> BlinkLogin.Failed(t.why)
        }
    }

    /** Trades the refresh token for new tokens. */
    suspend fun refresh(refreshToken: String, hardwareId: String): BlinkRefresh {
        val form = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refreshToken).add("client_id", CLIENT_ID)
            .add("scope", "client").add("hardware_id", hardwareId).build()
        return token(form)
    }

    private suspend fun token(form: FormBody): BlinkRefresh {
        val request = Request.Builder().url("${endpoints.oauth}/oauth/token").header("User-Agent", TOKEN_AGENT).header("Accept", "*/*").post(form).build()
        val r = send(http.value.newBuilder().callTimeout(CALL_S, TimeUnit.SECONDS).build(), request)
        if (r !is Raw.Done) return BlinkRefresh.Failed((r as Raw.Failed).why)
        if (r.code == 400 || r.code == 401 || r.code == 403) return BlinkRefresh.Rejected
        if (r.code == 429) return BlinkRefresh.Failed("Blink says to slow down; try again in a few minutes")
        if (r.code != 200) Log.w(LogTag.Home, "Blink: the token service answered HTTP ${r.code}")
        if (r.code != 200) return BlinkRefresh.Failed("Blink's sign-in service answered HTTP ${r.code}")
        val o = runCatching { json.parseToJsonElement(r.text) as? JsonObject }.getOrNull()
        val access = (o?.get("access_token") as? JsonPrimitive)?.contentOrNull
        val refresh = (o?.get("refresh_token") as? JsonPrimitive)?.contentOrNull
        if (access == null || refresh == null) return BlinkRefresh.Failed("Blink's sign-in service didn't return a session")
        return BlinkRefresh.Ok(BlinkTokens(access, refresh, (o["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600))
    }

    /** Which account and region the tokens belong to. */
    suspend fun tier(accessToken: String): BlinkReply<BlinkTier> =
        call(Request.Builder().url(endpoints.tierInfo).header("Authorization", "Bearer $accessToken").header("Accept", "application/json").get().build(), "the account") { text ->
            val o = json.parseToJsonElement(text) as? JsonObject ?: throw SerializationException("not an object")
            BlinkTier((o["tier"] as? JsonPrimitive)?.contentOrNull ?: throw SerializationException("no tier"), (o["account_id"] as? JsonPrimitive)?.contentOrNull ?: throw SerializationException("no account"))
        }

    // ---- the account ----

    suspend fun cameras(token: String, where: BlinkTier): BlinkReply<List<BlinkCamera>> =
        get(token, where, "/api/v3/accounts/${where.accountId}/homescreen", "the camera list") { BlinkParse.homescreen(json.parseToJsonElement(it)) }

    /** Asks a camera for a new snapshot. The answer is the command to wait for: its id and network. */
    suspend fun requestSnapshot(token: String, where: BlinkTier, camera: BlinkCamera): BlinkReply<Pair<String, String?>?> =
        send(token, where, "POST", BlinkParse.snapshotPath(where.accountId, camera), "the snapshot") { BlinkParse.command(json.parseToJsonElement(it)) }

    /** Whether the command Blink started has finished. */
    suspend fun commandDone(token: String, where: BlinkTier, network: String, commandId: String): BlinkReply<Boolean> =
        get(token, where, "/network/$network/command/$commandId", "the snapshot") {
            (json.parseToJsonElement(it) as? JsonObject)?.get("complete").let { c -> (c as? JsonPrimitive)?.booleanOrNull == true }
        }

    /** A camera's picture, from the address Blink gave for it. */
    suspend fun picture(token: String, where: BlinkTier, path: String): BlinkReply<ByteArray> {
        val url = absolute(where, path) ?: return BlinkReply.Failed("Blink gave an address Edith can't use")
        return bytes(Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "image/*").get().build(), "the picture") { it.startsWith("image/") }
    }

    /** One page of the media list (pages start at 1), newest first, since [sinceMs]. */
    suspend fun media(token: String, where: BlinkTier, sinceMs: Long, page: Int): BlinkReply<List<BlinkMedia>> =
        get(token, where, "/api/v1/accounts/${where.accountId}/media/changed?since=${java.net.URLEncoder.encode(BlinkParse.since(sinceMs), "UTF-8")}&page=$page", "the clips") {
            BlinkParse.media(json.parseToJsonElement(it))
        }

    /** The full address of a path on the account's server. */
    fun absolute(where: BlinkTier, path: String): HttpUrl? = runCatching { (endpoints.rest(where.tier) + path).toHttpUrl() }.getOrNull()

    // ---- HTTP ----

    private sealed interface Raw {
        data class Done(val code: Int, val text: String, val location: String?, val type: String) : Raw
        data class Failed(val why: String) : Raw
    }

    private suspend fun send(client: OkHttpClient, request: Request): Raw = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).await().use { r -> Raw.Done(r.code, r.body?.string().orEmpty(), r.header("Location"), r.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()) }
        } catch (e: IOException) {
            Raw.Failed("Blink can't be reached right now")
        }
    }

    private fun noRedirects(session: BlinkSignInSession) = session.client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    private fun formHeaders() = okhttp3.Headers.Builder()
        .add("User-Agent", PAGE_AGENT).add("Accept", "*/*").add("Origin", endpoints.oauth).add("Referer", "${endpoints.oauth}/oauth/v2/signin").build()

    private fun wantsCode(text: String): Boolean {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return false
        return listOf("tsv_state", "tsv_methods", "next_time_in_secs").any { o[it] != null && (o[it] as? JsonPrimitive)?.contentOrNull != "false" }
    }

    private suspend fun <T> get(token: String, where: BlinkTier, path: String, what: String, parse: (String) -> T): BlinkReply<T> = send(token, where, "GET", path, what, parse)

    private suspend fun <T> send(token: String, where: BlinkTier, method: String, path: String, what: String, parse: (String) -> T): BlinkReply<T> {
        val url = absolute(where, path) ?: return BlinkReply.Failed("Blink gave an address Edith can't use")
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json")
        return call(builder.method(method, if (method == "POST") ByteArray(0).toRequestBody() else null).build(), what, parse)
    }

    private suspend fun <T> call(request: Request, what: String, parse: (String) -> T): BlinkReply<T> {
        val r = send(http.value.newBuilder().callTimeout(CALL_S, TimeUnit.SECONDS).build(), request)
        if (r !is Raw.Done) return BlinkReply.Failed((r as Raw.Failed).why)
        return when {
            r.code == 401 -> BlinkReply.Unauthorized
            r.code == 409 -> busy(r.text)
            r.code == 429 -> BlinkReply.Failed("Blink says to slow down; try again in a few minutes")
            r.code !in 200..299 -> { Log.w(LogTag.Home, "Blink: HTTP ${r.code} for $what"); BlinkReply.Failed("Blink answered HTTP ${r.code} for $what") }
            else -> try { BlinkReply.Ok(parse(r.text)) } catch (e: SerializationException) { BlinkReply.Failed("Blink's answer for $what wasn't what Edith expected") }
                catch (e: IllegalArgumentException) { BlinkReply.Failed("Blink's answer for $what wasn't what Edith expected") }
        }
    }

    /** 409 also says the sync module is offline, which waiting won't change; any other 409 is "busy". */
    private fun busy(body: String): BlinkReply<Nothing> {
        val message = runCatching { ((json.parseToJsonElement(body) as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull }.getOrNull().orEmpty()
        return if (message.contains("offline", ignoreCase = true)) BlinkReply.Failed("Blink says the sync module is offline. Check that it is plugged in and online in the Blink app")
        else BlinkReply.Busy(message.ifBlank { "Blink is busy" }.take(120))
    }

    private suspend fun bytes(request: Request, what: String, typeOk: (String) -> Boolean): BlinkReply<ByteArray> = withContext(Dispatchers.IO) {
        try {
            http.value.newBuilder().callTimeout(IMAGE_S, TimeUnit.SECONDS).build().newCall(request).await().use { r ->
                val type = r.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                when {
                    r.code == 401 -> BlinkReply.Unauthorized
                    r.code == 409 -> busy(r.body?.string().orEmpty())
                    !r.isSuccessful -> BlinkReply.Failed("Blink answered HTTP ${r.code} for $what")
                    !typeOk(type) -> BlinkReply.Failed("Blink didn't send $what")
                    else -> (r.body?.bytes() ?: ByteArray(0)).let { if (it.isEmpty()) BlinkReply.Failed("Blink sent an empty $what") else BlinkReply.Ok(it) }
                }
            }
        } catch (e: IOException) {
            BlinkReply.Failed("Blink can't be reached right now")
        }
    }

    /** Cookies for one sign-in only: Blink's pages set some (a session) that the next steps send back. */
    private class MemoryCookies : CookieJar {
        private val held = mutableListOf<Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { c -> held.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }; held += c }
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = held.filter { it.matches(url) }
    }

    companion object {
        const val CLIENT_ID = "ios"
        const val APP_VERSION = "50.1"
        const val REDIRECT_URI = "immedia-blink://applinks.blink.com/signin/callback"
        /** What Blink's sign-in pages and token service expect to be talking to: its iPhone app. */
        const val PAGE_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.1 Mobile/15E148 Safari/604.1"
        const val TOKEN_AGENT = "Blink/2511191620 CFNetwork/3860.200.71 Darwin/25.1.0"
        private const val PAGE_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private const val CALL_S = 20L
        private const val IMAGE_S = 30L
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, r, _ -> r.close() }
    })
}

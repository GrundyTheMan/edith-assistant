package com.edith.app.blink

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import com.edith.app.core.net.HttpClients
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

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
    /** [status] is the HTTP status Blink refused with, or 0 when it did not answer. */
    data class Failed(val why: String, val status: Int = 0) : BlinkStart
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

    /**
     * Whether Blink is reached over IPv4 only. Set when Blink refused the normal route with 406 and then answered over IPv4: from
     * then on every Blink call goes that way. (Found on the owner's phone in Andarium, 2026-11-15: Blink's sign-in pages answered
     * 406 there, for its browser too, and 200 from the owner's PC. Nothing in the request's headers, user agent, TLS version or
     * protocol changed that from the PC; the way the phone is connected is the difference left, and IPv6 is one such way.)
     */
    @Volatile internal var ipv4Only = false
        private set

    private fun base(): OkHttpClient.Builder = http.value.newBuilder().also { if (ipv4Only) it.dns(IPV4_ONLY) }

    /**
     * Loads Blink's sign-in page the way its phone app does, to get its cookies and CSRF token. A 406 is tried once more over IPv4
     * only; if that works, IPv4 is used for Blink from then on, and if not, the answer says both routes were refused.
     */
    suspend fun begin(hardwareId: String): BlinkStart {
        val first = attempt(hardwareId, ipv4 = ipv4Only)
        if (first !is BlinkStart.Failed || first.status != 406 || ipv4Only) return first
        return when (val second = attempt(hardwareId, ipv4 = true)) {
            is BlinkStart.Ok -> {
                ipv4Only = true
                Log.w(LogTag.Home, "Blink refused the normal route (HTTP 406) but answered over IPv4: Blink is reached over IPv4 from now on")
                second
            }
            is BlinkStart.Failed -> BlinkStart.Failed("${first.why}. Over IPv4 only: ${second.why.removePrefix("Blink's sign-in page ")}", first.status)
        }
    }

    private suspend fun attempt(hardwareId: String, ipv4: Boolean): BlinkStart {
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val client = base().also { if (ipv4) it.dns(IPV4_ONLY) }.cookieJar(MemoryCookies()).callTimeout(CALL_S, TimeUnit.SECONDS).build()
        val authorize = URLBuilder("${endpoints.oauth}/oauth/v2/authorize").apply {
            listOf(
                "app_brand" to "blink", "app_version" to APP_VERSION, "client_id" to CLIENT_ID, "code_challenge" to challenge, "code_challenge_method" to "S256",
                "device_brand" to "Apple", "device_model" to "iPhone16,1", "device_os_version" to "26.1", "hardware_id" to hardwareId,
                "redirect_uri" to REDIRECT_URI, "response_type" to "code", "scope" to "client",
            ).forEach { (k, v) -> parameters.append(k, v) }
        }.buildString()
        val first = send(client, Req.Builder().url(authorize).header("User-Agent", PAGE_AGENT).header("Accept", PAGE_ACCEPT).header("Accept-Language", PAGE_LANGUAGE).get().build())
        if (first !is Raw.Done) return BlinkStart.Failed((first as Raw.Failed).why)
        if (first.code != 200) return refused("the first step (authorize)", first)
        val page = send(client, Req.Builder().url("${endpoints.oauth}/oauth/v2/signin").header("User-Agent", PAGE_AGENT).header("Accept", PAGE_ACCEPT).header("Accept-Language", PAGE_LANGUAGE).get().build())
        if (page !is Raw.Done) return BlinkStart.Failed((page as Raw.Failed).why)
        if (page.code != 200) return refused("the second step (the sign-in page)", page)
        val csrf = BlinkParse.csrfToken(page.text) ?: return BlinkStart.Failed("Blink's sign-in page wasn't what Edith expected (Blink may have changed it)")
        return BlinkStart.Ok(BlinkSignInSession(client, csrf, verifier, hardwareId))
    }

    /**
     * A sign-in page Blink would not give: says which step, the status and who answered (so a block by the network or by Blink's
     * front door shows as such), and a short excerpt of the page's own words. These are pages fetched before anything is typed, so
     * nothing of the owner's is in them. Logged too.
     */
    private suspend fun refused(step: String, r: Raw.Done): BlinkStart.Failed {
        val said = r.text.replace(Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
            .replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(100)
        val from = if (r.server.isNotEmpty()) ", from ${r.server.take(40)}" else ""
        // Which addresses Blink's server has for this phone: Blink's own servers' addresses, so nothing of the owner's.
        val addresses = withContext(Dispatchers.IO) {
            runCatching { java.net.InetAddress.getAllByName(Url(endpoints.oauth).host).take(4).joinToString { it.hostAddress.orEmpty() } }.getOrNull()
        }
        val where = if (!addresses.isNullOrEmpty()) ", reached at $addresses" else ""
        Log.w(LogTag.Home, "Blink: $step answered HTTP ${r.code}$from$where; the page says: \"$said\"")
        return BlinkStart.Failed("Blink's sign-in page answered HTTP ${r.code} at $step$from$where${if (said.isNotEmpty()) " (\"$said\")" else ""}", r.code)
    }

    /** The password step. Success or a code to type next; on success the tokens come back (the code is traded for them). */
    suspend fun signIn(session: BlinkSignInSession, email: String, password: String): BlinkLogin {
        val form = Form.Builder().add("username", email).add("password", password).add("csrf-token", session.csrf).build()
        val r = send(noRedirects(session), Req.Builder().url("${endpoints.oauth}/oauth/v2/signin").headers(formHeaders()).post(form).build())
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
        val form = Form.Builder().add("2fa_code", code).add("csrf-token", session.csrf).add("remember_me", "false").build()
        val r = send(noRedirects(session), Req.Builder().url("${endpoints.oauth}/oauth/v2/2fa/verify").headers(formHeaders()).post(form).build())
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
        val authorize = send(noRedirects(session), Req.Builder().url("${endpoints.oauth}/oauth/v2/authorize")
            .header("User-Agent", PAGE_AGENT).header("Accept", "*/*").header("Referer", "${endpoints.oauth}/oauth/v2/signin").get().build())
        if (authorize !is Raw.Done) return BlinkLogin.Failed((authorize as Raw.Failed).why)
        if (authorize.code !in REDIRECTS) Log.w(LogTag.Home, "Blink: the final authorize answered HTTP ${authorize.code}, not a redirect")
        val code = (if (authorize.code in REDIRECTS) BlinkParse.codeFrom(authorize.location) else null)
            ?: return BlinkLogin.Failed("Blink finished the sign-in without a code")
        val form = Form.Builder().add("app_brand", "blink").add("client_id", CLIENT_ID).add("code", code).add("code_verifier", session.verifier)
            .add("grant_type", "authorization_code").add("hardware_id", session.hardwareId).add("redirect_uri", REDIRECT_URI).add("scope", "client").build()
        return when (val t = token(form)) {
            is BlinkRefresh.Ok -> BlinkLogin.Ok(t.tokens)
            BlinkRefresh.Rejected -> BlinkLogin.Failed("Blink wouldn't trade the sign-in for a session")
            is BlinkRefresh.Failed -> BlinkLogin.Failed(t.why)
        }
    }

    /** Trades the refresh token for new tokens. */
    suspend fun refresh(refreshToken: String, hardwareId: String): BlinkRefresh {
        val form = Form.Builder().add("grant_type", "refresh_token").add("refresh_token", refreshToken).add("client_id", CLIENT_ID)
            .add("scope", "client").add("hardware_id", hardwareId).build()
        return token(form)
    }

    private suspend fun token(form: OutgoingContent): BlinkRefresh {
        val request = Req.Builder().url("${endpoints.oauth}/oauth/token").header("User-Agent", TOKEN_AGENT).header("Accept", "*/*").post(form).build()
        val r = send(base().callTimeout(CALL_S, TimeUnit.SECONDS).build(), request)
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
        call(Req.Builder().url(endpoints.tierInfo).header("Authorization", "Bearer $accessToken").header("Accept", "application/json").get().build(), "the account") { text ->
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

    /** Asks a camera for a live view (C32): the stream server's address and the command that keeps the view open. */
    suspend fun requestLive(token: String, where: BlinkTier, camera: BlinkCamera): BlinkReply<BlinkParse.LiveAnswer> =
        send(token, where, "POST", BlinkParse.liveviewPath(where.accountId, camera), "the live view") { BlinkParse.liveAnswer(json.parseToJsonElement(it)) }

    /** Null while a live view's command is still running (Blink ends a view by ending its command); otherwise what Blink said when it stopped: codes only. */
    suspend fun liveStatus(token: String, where: BlinkTier, network: String, commandId: String): BlinkReply<String?> =
        get(token, where, "/network/$network/command/$commandId", "the live view") { BlinkParse.commandStopped(json.parseToJsonElement(it)) }

    /** A camera's picture, from the address Blink gave for it. */
    suspend fun picture(token: String, where: BlinkTier, path: String): BlinkReply<ByteArray> {
        val url = absolute(where, path) ?: return BlinkReply.Failed("Blink gave an address Edith can't use")
        return bytes(Req.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "image/*").get().build(), "the picture") { it.startsWith("image/") }
    }

    /** One page of the media list (pages start at 1), newest first, since [sinceMs]. */
    suspend fun media(token: String, where: BlinkTier, sinceMs: Long, page: Int): BlinkReply<List<BlinkMedia>> =
        get(token, where, "/api/v1/accounts/${where.accountId}/media/changed?since=${java.net.URLEncoder.encode(BlinkParse.since(sinceMs), "UTF-8")}&page=$page", "the clips") {
            BlinkParse.media(json.parseToJsonElement(it))
        }

    /** The full address of a path on the account's server. */
    fun absolute(where: BlinkTier, path: String): Url? = runCatching { Url(endpoints.rest(where.tier) + path) }.getOrNull()

    // ---- HTTP ----

    private sealed interface Raw {
        data class Done(val code: Int, val text: String, val location: String?, val type: String, val server: String = "") : Raw
        data class Failed(val why: String) : Raw
    }

    /** [client] decides redirects (its own `followRedirects`), the cookie jar and the address lookup; Ktor makes the request over it. */
    private suspend fun send(client: OkHttpClient, request: Req): Raw = withContext(Dispatchers.IO) {
        try {
            val r = HttpClients.over(client).request(request.url) {
                method = request.method
                request.headers.forEach { (k, v) -> header(k, v) }
                request.body?.let { setBody(it) }
            }
            Raw.Done(r.status.value, r.bodyAsText(), r.headers["Location"], r.headers["Content-Type"].orEmpty().substringBefore(';').trim().lowercase(), r.headers["Server"].orEmpty())
        } catch (e: IOException) {
            Raw.Failed("Blink can't be reached right now")
        }
    }

    private fun noRedirects(session: BlinkSignInSession) = session.client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    private fun formHeaders() = mapOf(
        "User-Agent" to PAGE_AGENT, "Accept" to "*/*", "Origin" to endpoints.oauth, "Referer" to "${endpoints.oauth}/oauth/v2/signin",
    )

    private fun wantsCode(text: String): Boolean {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return false
        return listOf("tsv_state", "tsv_methods", "next_time_in_secs").any { o[it] != null && (o[it] as? JsonPrimitive)?.contentOrNull != "false" }
    }

    private suspend fun <T> get(token: String, where: BlinkTier, path: String, what: String, parse: (String) -> T): BlinkReply<T> = send(token, where, "GET", path, what, parse)

    private suspend fun <T> send(token: String, where: BlinkTier, method: String, path: String, what: String, parse: (String) -> T): BlinkReply<T> {
        val url = absolute(where, path) ?: return BlinkReply.Failed("Blink gave an address Edith can't use")
        val builder = Req.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json")
        return call(builder.method(method, if (method == "POST") ByteArrayContent(ByteArray(0)) else null).build(), what, parse)
    }

    private suspend fun <T> call(request: Req, what: String, parse: (String) -> T): BlinkReply<T> {
        val r = send(base().callTimeout(CALL_S, TimeUnit.SECONDS).build(), request)
        if (r !is Raw.Done) return BlinkReply.Failed((r as Raw.Failed).why)
        return when {
            r.code == 401 -> BlinkReply.Unauthorized
            r.code == 409 -> busy(r.text)
            r.code == 429 -> BlinkReply.Failed("Blink says to slow down; try again in a few minutes")
            r.code !in 200..299 -> {
                // Blink's own words ("message") say why; they hold no secrets, and are kept short.
                val said = runCatching { ((json.parseToJsonElement(r.text) as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull }.getOrNull()?.take(100)
                Log.w(LogTag.Home, "Blink: HTTP ${r.code} for $what${said?.let { ": $it" }.orEmpty()}")
                BlinkReply.Failed("Blink answered HTTP ${r.code} for $what")
            }
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

    private suspend fun bytes(request: Req, what: String, typeOk: (String) -> Boolean): BlinkReply<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val r = HttpClients.over(base().callTimeout(IMAGE_S, TimeUnit.SECONDS).build()).request(request.url) {
                method = request.method
                request.headers.forEach { (k, v) -> header(k, v) }
                request.body?.let { setBody(it) }
            }
            val type = r.headers["Content-Type"].orEmpty().substringBefore(';').trim().lowercase()
            val code = r.status.value
            when {
                code == 401 -> BlinkReply.Unauthorized
                code == 409 -> busy(r.bodyAsText())
                !r.status.isSuccess() -> BlinkReply.Failed("Blink answered HTTP $code for $what")
                !typeOk(type) -> BlinkReply.Failed("Blink didn't send $what")
                else -> r.bodyAsBytes().let { if (it.isEmpty()) BlinkReply.Failed("Blink sent an empty $what") else BlinkReply.Ok(it) }
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
        private const val PAGE_LANGUAGE = "en-US,en;q=0.9"
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        /** Only the IPv4 addresses of a host; with none, the lookup fails like an unknown host. */
        internal val IPV4_ONLY = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> =
                okhttp3.Dns.SYSTEM.lookup(hostname).filterIsInstance<java.net.Inet4Address>().ifEmpty { throw java.net.UnknownHostException("$hostname has no IPv4 address") }
        }
        private const val CALL_S = 20L
        private const val IMAGE_S = 30L
    }
}

/** A request, built the way Blink's steps read: an address, headers, a method and maybe a body. */
private class Req private constructor(val method: HttpMethod, val url: String, val headers: Map<String, String>, val body: OutgoingContent?) {
    class Builder {
        private var url = ""
        private var method = HttpMethod.Get
        private val headers = LinkedHashMap<String, String>()
        private var body: OutgoingContent? = null
        fun url(address: Any) = apply { url = address.toString() }
        fun header(name: String, value: String) = apply { headers[name] = value }
        fun headers(more: Map<String, String>) = apply { headers.putAll(more) }
        fun get() = apply { method = HttpMethod.Get }
        fun post(content: OutgoingContent) = apply { method = HttpMethod.Post; body = content }
        fun method(name: String, content: OutgoingContent?) = apply { method = HttpMethod.parse(name); body = content }
        fun build() = Req(method, url, headers, body)
    }
}

/** A form, as a body. */
private object Form {
    class Builder {
        private val fields = mutableListOf<Pair<String, String>>()
        fun add(name: String, value: String) = apply { fields += name to value }
        fun build(): OutgoingContent = FormDataContent(parameters { fields.forEach { (k, v) -> append(k, v) } })
    }
}

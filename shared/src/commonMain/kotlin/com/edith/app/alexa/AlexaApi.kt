@file:OptIn(ExperimentalEncodingApi::class)

package com.edith.app.alexa

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.edith.app.core.util.RandomSource
import com.edith.app.core.util.Sha256
import com.edith.app.core.util.systemRandom
import io.ktor.client.HttpClient
import io.ktor.http.decodeURLQueryComponent
import io.ktor.utils.io.charsets.Charsets
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.parameters
import io.ktor.http.takeFrom
import io.ktor.http.withCharset
import kotlinx.io.IOException

/** Where an Amazon site's three services live. Tests point all three at one pretend Amazon. */
data class AlexaEndpoints(val api: String, val www: String, val alexa: String) {
    companion object {
        fun of(site: String) = AlexaEndpoints("https://api.$site", "https://www.$site", "https://alexa.$site")
    }
}

/** What the owner's sign-in page needs to start and Edith needs to finish: the page, and the secrets that match it. */
data class AlexaSignInStart(
    val site: AmazonSite,
    /** Amazon's sign-in page, as the Alexa app opens it. */
    val url: String,
    val deviceId: String,
    /** PKCE: the page was given the hash of this, and registration must show the original. */
    val verifier: String,
    /** The two cookies the Alexa app's sign-in carries; made up here, as `alexa-cookie2` does. */
    val frc: String,
    val mapMd: String,
)

/** What the tokens Amazon hands back to a registered "device" look like once Edith keeps them. */
data class AlexaRegistration(val refreshToken: String, val deviceSerial: String)

/** Alexa's cookies for one Amazon site, and the csrf token its API wants with them. */
data class AlexaSession(val cookie: String, val csrf: String)

sealed interface AlexaReply<out T> {
    data class Ok<T>(val value: T) : AlexaReply<T>
    /** Amazon no longer accepts what was sent: the refresh token (sign in again), or the cookie (get new ones). */
    data object Rejected : AlexaReply<Nothing>
    data class Failed(val why: String) : AlexaReply<Nothing>
}

/**
 * Amazon's side of Alexa (C30), as `alexa-cookie2` 5.0.6 and `alexa-remote2` 8.1.1 (the libraries Jarvis uses) do it,
 * read on 2026-11-14: the sign-in page, registering Edith as an Alexa app "device" to get a refresh token, trading
 * that token for the cookies and csrf token Alexa's own web API takes, then the smart-home device list, states and
 * (part 2) commands. Unofficial: Amazon can change any of it. Tokens, cookies, passwords and response bodies are
 * never logged.
 */
class AlexaApi(
    private val http: Lazy<HttpClient>,
    private val endpointsFor: (AmazonSite) -> AlexaEndpoints = { AlexaEndpoints.of(it.site) },
    private val random: RandomSource = systemRandom(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Ktor over the OkHttp client, with no redirect following: [send] follows them by hand, so it can keep the cookies set along the way. */
    private val client by lazy { http.value.config { followRedirects = false } }

    // ---- Sign-in ----

    /** A new sign-in: Amazon's page for the Alexa app's "device authorization", with a fresh device id and PKCE pair. */
    fun start(site: AmazonSite): AlexaSignInStart {
        val deviceId = hex(hex(randomBytes(16)).uppercase().encodeToByteArray()) + DEVICE_TYPE_SUFFIX
        val verifier = base64Url(randomBytes(32))
        val challenge = base64Url(Sha256.digest(verifier.encodeToByteArray()))
        val handle = site.site.substringAfterLast('.').let { if (it == "com") "" else "_$it" }
        val host = "www.${site.site}"
        val url = "https://$host/ap/signin" +
            "?openid.return_to=https%3A%2F%2F$host%2Fap%2Fmaplanding" +
            "&openid.assoc_handle=amzn_dp_project_dee_ios$handle" +
            "&openid.identity=http%3A%2F%2Fspecs.openid.net%2Fauth%2F2.0%2Fidentifier_select" +
            "&pageId=amzn_dp_project_dee_ios$handle&accountStatusPolicy=P1" +
            "&openid.claimed_id=http%3A%2F%2Fspecs.openid.net%2Fauth%2F2.0%2Fidentifier_select" +
            "&openid.mode=checkid_setup" +
            "&openid.ns.oa2=http%3A%2F%2F$host%2Fap%2Fext%2Foauth%2F2" +
            "&openid.oa2.client_id=device%3A$deviceId" +
            "&openid.ns.pape=http%3A%2F%2Fspecs.openid.net%2Fextensions%2Fpape%2F1.0" +
            "&openid.oa2.response_type=code&openid.ns=http%3A%2F%2Fspecs.openid.net%2Fauth%2F2.0" +
            "&openid.pape.max_auth_age=0&openid.oa2.scope=device_auth_access" +
            "&openid.oa2.code_challenge_method=S256&openid.oa2.code_challenge=$challenge" +
            "&language=${site.language}"
        val frc = Base64.encode(randomBytes(313))
        val mapMd = Base64.encode(
            """{"device_user_dictionary":[],"device_registration_data":{"software_version":"1"},"app_identifier":{"app_version":"$APP_VERSION","bundle_id":"com.amazon.echo"}}""".encodeToByteArray(),
        )
        return AlexaSignInStart(site, url, deviceId, verifier, frc, mapMd)
    }

    /** The authorization code in the address the sign-in ended on (`…/ap/maplanding?openid.oa2.authorization_code=…`), or null. */
    fun authorizationCode(address: String): String? {
        val text = address.trim()
        if ("/ap/maplanding?" !in text) return null
        val parsed = runCatching { Url(text) }.getOrNull()?.parameters?.get(CODE_PARAM)
        if (parsed != null) return parsed.takeIf { it.isNotEmpty() }
        return Regex("[?&]" + Regex.escape(CODE_PARAM) + "=([^&#\\s]+)").find(text)?.groupValues?.get(1)
            ?.let { runCatching { it.decodeURLQueryComponent(plusIsSpace = true) }.getOrNull() }?.takeIf { it.isNotEmpty() }
    }

    /** Whether [address] is where the sign-in ends. */
    fun isFinish(address: String): Boolean = authorizationCode(address) != null

    /**
     * Registers Edith as an Alexa app "device" with the authorization code the sign-in gave: Amazon answers with the
     * refresh token (the only long-lived thing kept) and a few cookies, which aren't kept. [cookies] are what a WebView
     * holds for the site after signing in; empty when the browser did the signing in.
     */
    suspend fun register(start: AlexaSignInStart, code: String, cookies: String): AlexaReply<AlexaRegistration> {
        val e = endpointsFor(start.site)
        val serial = hex(randomBytes(16))
        val jar = CookieJar().apply { add(cookies); put("frc", start.frc); put("map-md", start.mapMd) }
        val body = buildJsonObject {
            put("requested_extensions", buildJsonArray { add(JsonPrimitive("device_info")); add(JsonPrimitive("customer_info")) })
            put("cookies", buildJsonObject {
                put("website_cookies", buildJsonArray { jar.entries().forEach { (n, v) -> add(buildJsonObject { put("Value", v); put("Name", n) }) } })
                put("domain", ".${start.site.site}")
            })
            put("registration_data", buildJsonObject {
                put("domain", "Device"); put("app_version", APP_VERSION); put("device_type", DEVICE_TYPE)
                put("device_name", "%FIRST_NAME%'s%DUPE_STRATEGY_1ST%$APP_NAME")
                put("os_version", "18.3.1"); put("device_serial", serial); put("device_model", "iPhone")
                put("app_name", APP_NAME); put("software_version", "1")
            })
            put("auth_data", buildJsonObject {
                put("client_id", start.deviceId); put("authorization_code", code)
                put("code_verifier", start.verifier); put("code_algorithm", "SHA-256"); put("client_domain", "DeviceLegacy")
            })
            put("user_context_map", buildJsonObject { put("frc", start.frc) })
            put("requested_token_type", buildJsonArray { add(JsonPrimitive("bearer")); add(JsonPrimitive("mac_dms")); add(JsonPrimitive("website_cookies")) })
        }
        val request = Req(HttpMethod.Post, "${e.api}/auth/register", appHeaders(start.site, "api.${start.site.site}") + ("Cookie" to jar.header()), TextContent(body.toString(), JSON))
        return when (val r = send(request)) {
            is Raw.Failed -> AlexaReply.Failed(r.why)
            is Raw.Done -> {
                val token = parse(r.body).obj("response").obj("success").obj("tokens").obj("bearer").str("refresh_token")
                if (r.code in 200..299 && token != null) AlexaReply.Ok(AlexaRegistration(token, serial))
                else if (r.code in 400..499) AlexaReply.Rejected.also { Log.w(LogTag.Home, "Alexa registration refused: HTTP ${r.code}") }
                else AlexaReply.Failed("Amazon answered HTTP ${r.code} when Edith registered")
            }
        }
    }

    // ---- Cookies for Alexa's web API ----

    /**
     * Alexa's cookies and csrf token for [site], from the refresh token: Amazon's token exchange gives the cookies,
     * and a page on `alexa.<site>` gives the csrf token. [AlexaReply.Rejected]: the refresh token is no good any more.
     */
    suspend fun session(site: AmazonSite, refreshToken: String): AlexaReply<AlexaSession> {
        val e = endpointsFor(site)
        val form = FormDataContent(parameters {
            append("di.os.name", "iOS"); append("app_version", APP_VERSION); append("domain", ".${site.site}")
            append("source_token", refreshToken); append("requested_token_type", "auth_cookies"); append("source_token_type", "refresh_token")
            append("di.hw.version", "iPhone"); append("di.sdk.version", "6.12.4"); append("app_name", APP_NAME); append("di.os.version", "16.6")
        })
        val request = Req(HttpMethod.Post, "${e.www}/ap/exchangetoken/cookies", appHeaders(site, "api.${site.site}") + ("Accept" to "*/*"), form)
        val jar = CookieJar()
        val exchange = send(request, jar)
        val cookies = when (exchange) {
            is Raw.Failed -> return AlexaReply.Failed(exchange.why)
            is Raw.Done -> {
                if (exchange.code in 400..499) return AlexaReply.Rejected
                val list = parse(exchange.body).obj("response").obj("tokens").obj("cookies").arr(".${site.site}")
                if (exchange.code !in 200..299 || list == null) return AlexaReply.Failed("Amazon gave no cookies (HTTP ${exchange.code})")
                list.forEach { c -> val o = c as? JsonObject; val n = o?.str("Name"); val v = o?.str("Value"); if (n != null && v != null) jar.put(n, v) }
                jar
            }
        }
        // The csrf token comes as a cookie from one of a few pages on the Alexa host.
        for (path in CSRF_PATHS) {
            val page = Req(
                HttpMethod.Get, "${e.alexa}$path",
                mapOf("User-Agent" to WEB_AGENT, "Referer" to "${e.alexa}/spa/index.html", "Origin" to e.alexa, "Accept" to "*/*", "Cookie" to cookies.header()),
            )
            val r = send(page, cookies)
            if (r is Raw.Failed) return AlexaReply.Failed(r.why)
            val csrf = cookies["csrf"]
            if (csrf != null) return AlexaReply.Ok(AlexaSession(cookies.header(), csrf))
        }
        return AlexaReply.Failed("Alexa gave no csrf token")
    }

    // ---- Smart home ----

    /** The smart-home endpoints on the account (Alexa's device service). */
    suspend fun endpoints(site: AmazonSite, session: AlexaSession): AlexaReply<JsonElement> {
        val body = buildJsonObject { put("query", ENDPOINTS_QUERY) }
        return when (val r = alexaCall(site, session, "/nexus/v1/graphql", "POST", body.toString(), timeoutS = 40)) {
            is AlexaReply.Ok -> {
                val items = parse(r.value).obj("data").obj("endpoints").arr("items")
                if (items != null) AlexaReply.Ok(items) else AlexaReply.Failed("Alexa's device list wasn't what Edith expected")
            }
            AlexaReply.Rejected -> AlexaReply.Rejected
            is AlexaReply.Failed -> r
        }
    }

    /** The state of the devices named in [stateRequests] ([AlexaParse.stateRequests]). */
    suspend fun states(site: AmazonSite, session: AlexaSession, stateRequests: JsonArray, timeoutS: Long): AlexaReply<JsonElement> {
        val body = buildJsonObject { put("stateRequests", stateRequests) }
        return when (val r = alexaCall(site, session, "/api/phoenix/state", "POST", body.toString(), timeoutS)) {
            is AlexaReply.Ok -> AlexaReply.Ok(parse(r.value))
            AlexaReply.Rejected -> AlexaReply.Rejected
            is AlexaReply.Failed -> r
        }
    }

    /**
     * Sends one control request ([AlexaParse.controlParameters]) to the device with [entityId]. Amazon answers 200 even
     * when it refuses, so the caller reads [AlexaParse.controlProblem] on the answer.
     */
    suspend fun control(site: AmazonSite, session: AlexaSession, entityId: String, parameters: JsonObject, timeoutS: Long): AlexaReply<JsonElement> {
        val body = buildJsonObject {
            put("controlRequests", JsonArray(listOf(buildJsonObject {
                put("entityId", entityId); put("entityType", "APPLIANCE"); put("parameters", parameters)
            })))
        }
        return when (val r = alexaCall(site, session, "/api/phoenix/state", "PUT", body.toString(), timeoutS)) {
            is AlexaReply.Ok -> AlexaReply.Ok(parse(r.value))
            AlexaReply.Rejected -> AlexaReply.Rejected
            is AlexaReply.Failed -> r
        }
    }

    private suspend fun alexaCall(site: AmazonSite, session: AlexaSession, path: String, method: String, body: String?, timeoutS: Long): AlexaReply<String> {
        val e = endpointsFor(site)
        val request = Req(
            HttpMethod.parse(method), "${e.alexa}$path",
            mapOf(
                "User-Agent" to "$OFFICIAL_AGENT Edith", "Accept" to "application/json; charset=utf-8",
                "Accept-Language" to site.language.replace('_', '-'), "Referer" to "${e.alexa}/spa/index.html",
                "Origin" to e.alexa, "csrf" to session.csrf, "Cookie" to session.cookie,
            ),
            body?.let { TextContent(it, ContentType.Application.Json.withCharset(Charsets.UTF_8)) },
        )
        return when (val r = send(request, null, timeoutS)) {
            is Raw.Failed -> AlexaReply.Failed(r.why)
            is Raw.Done -> when {
                // Expired cookies: Amazon answers 401/403, or sends the caller to its sign-in page.
                r.code == 401 || r.code == 403 || r.signedOut -> AlexaReply.Rejected
                r.code in 200..299 -> AlexaReply.Ok(r.body)
                else -> AlexaReply.Failed("Amazon answered HTTP ${r.code}")
            }
        }
    }

    // ---- HTTP ----

    private sealed interface Raw {
        data class Done(val code: Int, val body: String, val signedOut: Boolean) : Raw
        data class Failed(val why: String) : Raw
    }

    /** What to send: kept so a redirect can send it again to the next address. */
    private class Req(val method: HttpMethod, val url: String, val headers: Map<String, String>, val body: OutgoingContent? = null)

    /** One request, following redirects by hand so the cookies set along the way are kept in [jar]. */
    private suspend fun send(first: Req, jar: CookieJar? = null, timeoutS: Long = CALL_S): Raw {
        var request = first
        return try {
            repeat(MAX_REDIRECTS + 1) {
                val r = client.request(request.url) {
                    method = request.method
                    timeout { requestTimeoutMillis = timeoutS * 1000 }
                    request.headers.forEach { (k, v) -> header(k, v) }
                    request.body?.let { setBody(it) }
                }
                jar?.add(r.headers.getAll("Set-Cookie").orEmpty())
                val location = r.headers[HttpHeaders.Location]
                if (r.status.value in REDIRECTS && location != null) {
                    val next = runCatching { Url(request.url).resolveAgainst(location) }.getOrNull() ?: return Raw.Failed("Amazon sent Edith somewhere it didn't expect")
                    // A redirect to Amazon's sign-in page means the cookies are no good.
                    if ("/ap/signin" in next.encodedPath) return Raw.Done(401, "", signedOut = true)
                    if (next.protocol.name != "https") return Raw.Failed("Amazon sent Edith to a page that isn't secure")
                    request = Req(HttpMethod.Get, next.toString(), request.headers.let { h -> jar?.let { h + ("Cookie" to it.header()) } ?: h })
                } else {
                    return Raw.Done(r.status.value, r.bodyAsText(), signedOut = false)
                }
            }
            Raw.Failed("Amazon redirected Edith too many times")
        } catch (e: IOException) {
            Raw.Failed("Amazon can't be reached right now")
        }
    }

    private fun Url.resolveAgainst(reference: String): Url = URLBuilder(this).takeFrom(reference).build()

    private fun appHeaders(site: AmazonSite, identityDomain: String) = mapOf(
        "User-Agent" to APP_AGENT, "Accept-Language" to site.language.replace('_', '-'), "Accept-Charset" to "utf-8",
        "Accept" to "application/json", "x-amzn-identity-auth-domain" to identityDomain,
    )

    private fun parse(text: String): JsonObject = try { json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap()) } catch (e: SerializationException) { JsonObject(emptyMap()) }
    private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun base64Url(bytes: ByteArray) = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

    companion object {
        const val CODE_PARAM = "openid.oa2.authorization_code"
        const val APP_NAME = "Edith"
        private const val APP_VERSION = "2.2.651540.0"
        /** The Alexa app for iPhone's device type: what Amazon expects of a registered "device" (as `alexa-cookie2`). */
        private const val DEVICE_TYPE = "A2IVLV5VM2W81"
        /** `#A2IVLV5VM2W81` in hex, which ends every device id. */
        private const val DEVICE_TYPE_SUFFIX = "23413249564c5635564d32573831"
        private const val APP_AGENT = "AmazonWebView/Amazon Alexa/2.2.651540.0/iOS/18.3.1/iPhone"
        private const val OFFICIAL_AGENT = "AppleWebKit PitanguiBridge/2.2.595606.0-[HARDWARE=iPhone14_7][SOFTWARE=17.4.1][DEVICE=iPhone]"
        private const val WEB_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.0.0 Safari/537.36"
        private val CSRF_PATHS = listOf("/api/language", "/spa/index.html", "/api/devices-v2/device?cached=false", "/templates/oobe/d-device-pick.handlebars", "/api/strings")
        private const val CALL_S = 20L
        private const val MAX_REDIRECTS = 5
        private val REDIRECTS = setOf(300, 301, 302, 303, 307, 308)
        private val JSON = ContentType.Application.Json

        /** Alexa's device list, as `alexa-remote2` asks for it (the whole query, as proven). */
        val ENDPOINTS_QUERY = """query Endpoints {
  endpoints {
    items {
      endpointId
      id
      friendlyName
      displayCategories {
        primary {
          value
        }
      }
      legacyIdentifiers {
          dmsIdentifier {
              deviceType {
                  type
                  value {
                      text
                  }
              }
              deviceSerialNumber {
                  type
                  value {
                      text
                  }
              }
          }
      }
      legacyAppliance {
        applianceId
        applianceTypes
        endpointTypeId
        friendlyName
        friendlyDescription
        manufacturerName
        connectedVia
        modelName
        entityId
        actions
        mergedApplianceIds
        capabilities
        applianceNetworkState
        version
        isEnabled
        customerDefinedDeviceType
        customerPreference
        alexaDeviceIdentifierList
        aliases
        driverIdentity
        additionalApplianceDetails
        isConsentRequired
        applianceKey
        appliancePairs
        deduplicatedPairs
        entityPairs
        deduplicatedAliasesByEntityId
        relations
      }
      serialNumber {
        value {
          text
        }
      }
      enablement
      model {
        value {
          text
        }
      }
      manufacturer {
        value {
          text
        }
      }
      features {
        name
        operations {
          name
        }
      }
    }
  }
}
"""
    }
}

/** The cookies of one conversation with Amazon: names and values, in the order they were first set. */
internal class CookieJar {
    private val values = LinkedHashMap<String, String>()

    operator fun get(name: String): String? = values[name]
    fun put(name: String, value: String) { values[name] = value }
    /**
     * The cookies a request can carry. A header holds only printable ASCII: a cookie with anything else in its name or value
     * (an unusual web view's, or one Amazon sets with a stray character) is left out, because OkHttp refuses the whole request
     * with an exception otherwise (it crashed the sign-in on the owner's phone).
     */
    fun entries(): List<Pair<String, String>> = values.entries.filter { sendable(it.key) && it.key.isNotEmpty() && sendable(it.value) }.map { it.key to it.value }

    private fun sendable(text: String) = text.all { it.code in 0x20..0x7e }

    /** A `Cookie:` header's text (`a=1; b=2`). */
    fun add(header: String) {
        // Cookies are separated by ";", and by line breaks in some web views.
        for (part in header.split(';', '\n', '\r')) {
            val i = part.indexOf('=')
            if (i > 0) values[part.substring(0, i).trim()] = part.substring(i + 1).trim()
        }
    }

    /** `Set-Cookie` header lines: only each one's `name=value`. */
    fun add(setCookies: List<String>) {
        for (line in setCookies) {
            val first = line.substringBefore(';')
            val i = first.indexOf('=')
            if (i <= 0) continue
            val value = first.substring(i + 1).trim()
            if (value == "\"\"") continue
            values[first.substring(0, i).trim()] = value
        }
    }

    fun header(): String = entries().joinToString("; ") { "${it.first}=${it.second}" }
}

package com.edith.app.ring

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.home.DeviceEvent
import com.edith.app.home.EventKind
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.content.TextContent
import kotlinx.io.IOException
import kotlin.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Where Ring's three services live. Tests point all three at one pretend server. */
data class RingEndpoints(
    val oauth: String = "https://oauth.ring.com",
    val clients: String = "https://api.ring.com/clients_api",
    val snapshots: String = "https://app-snaps.ring.com",
    val liveView: String = "https://api.ring.com/integrations/v1/liveview",
)

/** The tokens Ring hands out. [refreshToken] rotates: the one it replaces stops working soon after. */
data class RingTokens(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long)

/** How a sign-in ended (C27 §1). */
sealed interface RingLogin {
    data class Ok(val tokens: RingTokens) : RingLogin
    /** Ring sent a code (HTTP 412); [prompt] says where ("sent to +1… via sms", "from your authenticator app"). */
    data class NeedsCode(val prompt: String) : RingLogin
    data object WrongCode : RingLogin
    data object BadLogin : RingLogin
    data object TooManyCodes : RingLogin
    data class Failed(val why: String) : RingLogin
}

/** A refresh of the access token. */
sealed interface RingRefresh {
    data class Ok(val tokens: RingTokens) : RingRefresh
    /** Ring no longer accepts the refresh token: only signing in again helps. */
    data object Rejected : RingRefresh
    data class Failed(val why: String) : RingRefresh
}

/** The answer to one call with an access token. */
sealed interface RingReply<out T> {
    data class Ok<T>(val value: T) : RingReply<T>
    /** HTTP 401: the access token is no good (expired): refresh and ask again. */
    data object Unauthorized : RingReply<Nothing>
    /** HTTP 404 naming this phone's hardware id: Ring forgot the session; start one again. */
    data object NoSession : RingReply<Nothing>
    data class Failed(val why: String) : RingReply<Nothing>
}

/** One camera or doorbell on the account, as Ring lists it. */
data class RingCamera(
    val id: String,
    val name: String,
    val doorbell: Boolean,
    val locationId: String?,
    val offline: Boolean,
    val battery: Int?,
    /** Ring's "Record Motion" is off: it then gives no pictures (the library's `snapshotsAreBlocked`). */
    val motionDetection: Boolean,
)

/**
 * Ring's own (unofficial) service, as the maintained `ring-client-api` uses it (read from v14.3.0 on 2026-11-14):
 * OAuth at `oauth.ring.com` (password grant with the 2FA code in a header, then the rotating refresh-token grant),
 * a session for this phone's hardware id, the device list, snapshots and event history. Stateless: [RingService]
 * keeps the tokens and decides when to refresh. Tokens, passwords, pictures and response bodies are never logged.
 */
class RingApi(
    private val http: Lazy<HttpClient>,
    private val endpoints: RingEndpoints = RingEndpoints(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Step one and two of a sign-in: email and password, plus [code] once Ring has asked for it. */
    suspend fun signIn(email: String, password: String, code: String?, hardwareId: String): RingLogin {
        val body = buildJsonObject {
            put("client_id", CLIENT_ID); put("scope", "client")
            put("grant_type", "password"); put("username", email); put("password", password)
        }
        val raw = oauth(body, hardwareId, code)
        if (raw is Raw.Failed) return RingLogin.Failed(raw.why)
        raw as Raw.Done
        val data = raw.body
        return when {
            raw.code == 200 -> tokens(data)?.let { RingLogin.Ok(it) } ?: RingLogin.Failed("Ring's answer wasn't what Edith expected")
            raw.code == 412 -> RingLogin.NeedsCode(promptFor(data))
            raw.code == 400 && data.text("error")?.startsWith("Verification Code") == true -> RingLogin.WrongCode
            data.text("error_description") == "too many requests from dependency service" -> RingLogin.TooManyCodes
            raw.code == 400 || raw.code == 401 -> RingLogin.BadLogin
            else -> RingLogin.Failed("Ring answered HTTP ${raw.code}")
        }
    }

    /** The refresh-token grant. The reply's `refresh_token` replaces the one sent. */
    suspend fun refresh(refreshToken: String, hardwareId: String): RingRefresh {
        val body = buildJsonObject {
            put("client_id", CLIENT_ID); put("scope", "client")
            put("grant_type", "refresh_token"); put("refresh_token", refreshToken)
        }
        val raw = oauth(body, hardwareId, null)
        if (raw is Raw.Failed) return RingRefresh.Failed(raw.why)
        raw as Raw.Done
        return when {
            raw.code == 200 -> tokens(raw.body)?.let { RingRefresh.Ok(it) } ?: RingRefresh.Failed("Ring's answer wasn't what Edith expected")
            raw.code == 400 || raw.code == 401 -> RingRefresh.Rejected
            else -> RingRefresh.Failed("Ring answered HTTP ${raw.code}")
        }
    }

    /** Starts this phone's session: Ring wants one before it answers the other calls. */
    suspend fun startSession(accessToken: String, hardwareId: String): RingReply<Unit> {
        val body = buildJsonObject {
            put("device", buildJsonObject {
                put("hardware_id", hardwareId)
                put("metadata", buildJsonObject { put("api_version", API_VERSION); put("device_model", "Edith") })
                put("os", "android")
            })
        }
        val request = Req(HttpMethod.Post, "${endpoints.clients}/session", mapOf("authorization" to "Bearer $accessToken", "Accept" to "application/json"), body.toString())
        return call(request, hardwareId, "the session") { Unit }
    }

    /**
     * Starts a live view: Edith's WebRTC offer goes to Ring, which answers with its own SDP (`integrations/v1/liveview/start`, as
     * `ring-client-api`'s `SimpleWebRtcSession`, read from v14.3.0). [sessionId] is made by Edith and names the view for [liveEnd].
     */
    suspend fun liveStart(accessToken: String, hardwareId: String, cameraId: String, sessionId: String, offerSdp: String): RingReply<String> {
        // Ring's device ids are numbers, and ring-client-api sends this one as a number (Ring answered HTTP 400 to a string, 2026-11-17).
        val body = buildJsonObject {
            put("session_id", sessionId)
            val numeric = cameraId.toLongOrNull()
            if (numeric != null) put("device_id", numeric) else put("device_id", cameraId)
            put("sdp", offerSdp)
            put("protocol", "webrtc")
        }
        val request = Req(HttpMethod.Post, "${endpoints.liveView}/start", authHeaders(accessToken, hardwareId) + ("Accept" to "application/json"), body.toString())
        return call(request, hardwareId, "the live view") { text ->
            ((json.parseToJsonElement(text) as? JsonObject)?.get("sdp") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw SerializationException("no sdp")
        }
    }

    /** Ends a live view, so the camera stops streaming. */
    suspend fun liveEnd(accessToken: String, hardwareId: String, sessionId: String): RingReply<Unit> {
        val body = buildJsonObject { put("session_id", sessionId) }
        val request = Req(HttpMethod.Post, "${endpoints.liveView}/end", authHeaders(accessToken, hardwareId) + ("Accept" to "application/json"), body.toString())
        return call(request, hardwareId, "the live view") { Unit }
    }

    /** The cameras and doorbells (not the chimes, base stations or sensors). */
    suspend fun cameras(accessToken: String, hardwareId: String): RingReply<List<RingCamera>> =
        get("${endpoints.clients}/ring_devices", accessToken, hardwareId, "the device list") { text ->
            val root = json.parseToJsonElement(text) as? JsonObject ?: throw SerializationException("not an object")
            val doorbots = root.array("doorbots").mapNotNull { camera(it, doorbell = true) }
            val sticks = root.array("stickup_cams").mapNotNull { camera(it, doorbell = false) }
            val shared = root.array("authorized_doorbots").mapNotNull { camera(it, doorbell = true) }
            val other = root.array("other").filter { it.obj()?.text("kind") == "onvif_camera" }.mapNotNull { camera(it, doorbell = false) }
            (doorbots + sticks + shared + other).distinctBy { it.id }
        }

    /** A fresh picture: Ring has the camera take one now (`extras=force`) and returns the JPEG. */
    suspend fun snapshot(accessToken: String, hardwareId: String, cameraId: String, afterMs: Long): RingReply<ByteArray> {
        val request = Req(HttpMethod.Get, "${endpoints.snapshots}/snapshots/next/$cameraId?after-ms=$afterMs&extras=force", authHeaders(accessToken, hardwareId) + ("accept" to "image/jpeg"))
        return try {
            val r = send(request, SNAPSHOT_CALL_S)
            when {
                r.code == 401 -> RingReply.Unauthorized
                !r.ok -> RingReply.Failed("Ring answered HTTP ${r.code} for the picture")
                else -> if (r.bytes.isEmpty()) RingReply.Failed("Ring sent an empty picture") else RingReply.Ok(r.bytes)
            }
        } catch (e: IOException) {
            RingReply.Failed("Ring can't be reached right now")
        }
    }

    /** What the camera reported since [sinceMs], newest first. */
    suspend fun events(accessToken: String, hardwareId: String, camera: RingCamera, sinceMs: Long): RingReply<List<DeviceEvent>> {
        val location = camera.locationId ?: return RingReply.Failed("Ring didn't say where this camera is")
        val url = "${endpoints.clients}/locations/$location/devices/${camera.id}/events?limit=$EVENT_LIMIT"
        return get(url, accessToken, hardwareId, "the events") { text ->
            val root = json.parseToJsonElement(text) as? JsonObject ?: throw SerializationException("not an object")
            root.array("events").mapNotNull { event(it) }.filter { it.at >= sinceMs }.sortedByDescending { it.at }
        }
    }

    /**
     * The clips Ring recorded for [camera] between [sinceMs] and [untilMs] (`video_search/history`). Ring keeps clips
     * only with a Ring Protect plan; without one the search still lists the events, with `had_subscription` false.
     */
    suspend fun clips(accessToken: String, hardwareId: String, camera: RingCamera, sinceMs: Long, untilMs: Long): RingReply<List<RingClipRow>> {
        val url = URLBuilder("${endpoints.clients}/video_search/history").apply {
            parameters.append("doorbot_id", camera.id); parameters.append("date_from", sinceMs.toString())
            parameters.append("date_to", untilMs.toString()); parameters.append("order", "desc")
            parameters.append("api_version", API_VERSION.toString()); parameters.append("includes[]", "pva")
        }.buildString()
        return get(url, accessToken, hardwareId, "the clips") { text ->
            val root = json.parseToJsonElement(text) as? JsonObject ?: throw SerializationException("not an object")
            root.array("video_search").mapNotNull { clipRow(it, camera) }
        }
    }
    /**
     * A signed address to stream clip [dingId] from, asked for at play time because it expires: `dings/{id}/share/play` (the video as
     * recorded, `ring-client-api`'s default) or, with [transcoded], `dings/{id}/recording`. Ring answers 404 to one when it has only the other.
     */
    suspend fun clipUrl(accessToken: String, hardwareId: String, dingId: String, transcoded: Boolean): RingReply<String> =
        get("${endpoints.clients}/dings/$dingId/${if (transcoded) "recording" else "share/play"}?disable_redirect=true", accessToken, hardwareId, "the clip") { text ->
            (json.parseToJsonElement(text) as? JsonObject)?.text("url")?.takeIf { it.startsWith("https://") } ?: throw SerializationException("no address")
        }

    fun clipRow(e: JsonElement, camera: RingCamera): RingClipRow? {
        val d = e.obj() ?: return null
        val id = d["ding_id"].idText() ?: return null
        val at = (d["created_at"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.let { s -> runCatching { Instant.parse(s).toEpochMilliseconds() }.getOrNull() } } ?: return null
        val cv = d["cv_properties"].obj()
        val person = (cv?.get("person_detected") as? JsonPrimitive)?.booleanOrNull == true || cv?.text("detection_type") == "human"
        val kind = when {
            person -> EventKind.Person
            d.text("kind") == "ding" -> EventKind.Ring
            d.text("kind") == "motion" -> EventKind.Motion
            d.text("kind") == "on_demand" -> EventKind.LiveView
            else -> EventKind.Other
        }
        val seconds = (d["duration"] as? JsonPrimitive)?.doubleOrNull?.toInt()
        // The search lists addresses of its own (signed, they expire): kept as a last resort for playing.
        val url = listOf("lq_url", "hq_url", "untranscoded_url").firstNotNullOfOrNull { d.text(it)?.takeIf { u -> u.startsWith("https://") } }
        return RingClipRow(id, camera.name, at, kind, seconds, hadPlan = (d["had_subscription"] as? JsonPrimitive)?.booleanOrNull != false, url = url)
    }

    // ---- parsing (internal: the tests use them) ----

    fun camera(e: JsonElement, doorbell: Boolean): RingCamera? {
        val d = e.obj() ?: return null
        val id = d["id"].idText() ?: return null
        val kind = d.text("kind").orEmpty()
        return RingCamera(
            id = id,
            name = d.text("description") ?: "Ring camera",
            doorbell = doorbell || kind.startsWith("doorbell"),
            locationId = d.text("location_id"),
            offline = d["alerts"].obj()?.text("connection") == "offline",
            battery = battery(d),
            motionDetection = d["settings"].obj()?.get("motion_detection_enabled").let { (it as? JsonPrimitive)?.booleanOrNull != false },
        )
    }

    /** The library's `getBatteryLevel`: the lower of the two batteries; null for a wired camera. */
    fun battery(d: JsonObject): Int? {
        fun level(e: JsonElement?): Double? = (e as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
        val levels = listOfNotNull(level(d["battery_life"]), level(d["battery_life_2"]))
        val health = d["health"].obj()
        val none = health != null && listOf("battery_percentage", "battery_present", "second_battery_percentage").none { key ->
            (health[key] as? JsonPrimitive)?.let { it.booleanOrNull == true || (it.doubleOrNull ?: 0.0) != 0.0 } == true
        }
        return if (levels.isEmpty() || none) null else levels.min().toInt().coerceIn(0, 100)
    }

    fun event(e: JsonElement): DeviceEvent? {
        val d = e.obj() ?: return null
        val at = d.text("created_at")?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: return null
        val cv = d["cv_properties"].obj()
        val person = (cv?.get("person_detected") as? JsonPrimitive)?.let { it.booleanOrNull == true } == true ||
            cv?.text("detection_type") == "human"
        val kind = when {
            person -> EventKind.Person
            d.text("kind") == "ding" -> EventKind.Ring
            d.text("kind") == "motion" -> EventKind.Motion
            d.text("kind") == "on_demand" -> EventKind.LiveView
            else -> EventKind.Other
        }
        return DeviceEvent(at, kind)
    }

    // ---- HTTP ----

    private sealed interface Raw {
        data class Done(val code: Int, val body: JsonObject) : Raw
        data class Failed(val why: String) : Raw
    }

    private suspend fun oauth(body: JsonObject, hardwareId: String, code: String?): Raw {
        val request = Req(
            HttpMethod.Post, "${endpoints.oauth}/oauth/token",
            mapOf("2fa-support" to "true", "2fa-code" to code.orEmpty(), "hardware_id" to hardwareId, "User-Agent" to USER_AGENT, "Accept" to "application/json"),
            body.toString(),
        )
        return try {
            val r = send(request, CALL_S)
            Raw.Done(r.code, runCatching { json.parseToJsonElement(r.text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap()))
        } catch (e: IOException) {
            Raw.Failed("Ring can't be reached right now")
        }
    }

    private fun authHeaders(accessToken: String, hardwareId: String) =
        mapOf("authorization" to "Bearer $accessToken", "hardware_id" to hardwareId, "User-Agent" to USER_AGENT)

    private suspend fun <T> get(url: String, accessToken: String, hardwareId: String, what: String, parse: (String) -> T): RingReply<T> {
        val request = Req(HttpMethod.Get, url, authHeaders(accessToken, hardwareId) + ("Accept" to "application/json"))
        return call(request, hardwareId, what, parse)
    }

    /** One request: what to send, held together so [call] and [oauth] share how it is sent. */
    private class Req(val method: HttpMethod, val url: String, val headers: Map<String, String>, val body: String? = null)

    private class Answer(val code: Int, val bytes: ByteArray) {
        val ok get() = code in 200..299
        val text get() = bytes.decodeToString()
    }

    private suspend fun send(request: Req, timeoutS: Long): Answer {
        val r = http.value.request(request.url) {
            method = request.method
            timeout { requestTimeoutMillis = timeoutS * 1000 }
            request.headers.forEach { (k, v) -> header(k, v) }
            request.body?.let { setBody(TextContent(it, JSON)) }
        }
        return Answer(r.status.value, r.bodyAsBytes())
    }

    private suspend fun <T> call(request: Req, hardwareId: String, what: String, parse: (String) -> T): RingReply<T> = try {
        val r = send(request, CALL_S)
        val text = r.text
        when {
            r.code == 401 -> RingReply.Unauthorized
            r.code == 404 && hardwareId in text -> RingReply.NoSession
            r.code == 429 -> RingReply.Failed("Ring says to slow down; try again in a few minutes")
            !r.ok -> {
                // For a live view, Ring's own words (its error text, never the request) say what it didn't like.
                val why = if (what == "the live view") reason(text)?.let { " ($it)" }.orEmpty() else ""
                Log.w(LogTag.Home, "Ring: HTTP ${r.code} for $what$why")
                RingReply.Failed("Ring answered HTTP ${r.code} for $what$why")
            }
            else -> try { RingReply.Ok(parse(text)) } catch (e: SerializationException) { RingReply.Failed("Ring's answer for $what wasn't what Edith expected") }
        }
    } catch (e: IOException) {
        RingReply.Failed("Ring can't be reached right now")
    }

    /** The error text in one of Ring's failure bodies, short, or null. Used only where it helps the owner (a refused live view). */
    private fun reason(body: String): String? {
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        return listOf("error", "message", "error_description", "error_message").firstNotNullOfOrNull { k -> o.text(k) }?.take(120)
    }

    private fun tokens(d: JsonObject): RingTokens? {
        val access = d.text("access_token") ?: return null
        return RingTokens(access, d.text("refresh_token"), d["expires_in"].let { (it as? JsonPrimitive)?.longOrNull } ?: 3600)
    }

    private fun promptFor(d: JsonObject): String {
        val state = d.text("tsv_state")
        return when {
            state == "totp" -> "Ring wants the code from your authenticator app."
            state != null -> "Ring sent a code to ${d.text("phone") ?: "you"} by $state."
            else -> "Ring sent a code to your phone or email."
        }
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.idText(): String? = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    companion object {
        const val CLIENT_ID = "ring_official_android"
        const val USER_AGENT = "android:com.ringapp"
        const val API_VERSION = 11
        const val EVENT_LIMIT = 50
        private const val CALL_S = 20L
        private const val SNAPSHOT_CALL_S = 30L
        private val JSON = ContentType.Application.Json
    }
}

/** One clip as Ring's video search lists it. [hadPlan]: it was recorded under a Ring Protect plan (false: nothing was kept). */
data class RingClipRow(val id: String, val cameraName: String, val at: Long, val kind: EventKind, val seconds: Int?, val hadPlan: Boolean, val url: String? = null)

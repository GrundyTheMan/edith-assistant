package com.edith.app.ring

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.home.DeviceEvent
import com.edith.app.home.EventKind
import kotlinx.coroutines.suspendCancellableCoroutine
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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Where Ring's three services live. Tests point all three at one pretend server. */
data class RingEndpoints(
    val oauth: String = "https://oauth.ring.com",
    val clients: String = "https://api.ring.com/clients_api",
    val snapshots: String = "https://app-snaps.ring.com",
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
    private val http: Lazy<OkHttpClient>,
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
        val request = Request.Builder().url("${endpoints.clients}/session")
            .header("authorization", "Bearer $accessToken").header("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON)).build()
        return call(request, hardwareId, "the session") { Unit }
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
        val url = "${endpoints.snapshots}/snapshots/next/$cameraId".toHttpUrl().newBuilder()
            .addQueryParameter("after-ms", afterMs.toString()).addQueryParameter("extras", "force").build()
        val request = Request.Builder().url(url).headers(authHeaders(accessToken, hardwareId)).header("accept", "image/jpeg").get().build()
        return try {
            http.value.newBuilder().callTimeout(SNAPSHOT_CALL_S, TimeUnit.SECONDS).build().newCall(request).await().use { r ->
                when {
                    r.code == 401 -> RingReply.Unauthorized
                    !r.isSuccessful -> RingReply.Failed("Ring answered HTTP ${r.code} for the picture")
                    else -> {
                        val bytes = r.body?.bytes() ?: ByteArray(0)
                        if (bytes.isEmpty()) RingReply.Failed("Ring sent an empty picture") else RingReply.Ok(bytes)
                    }
                }
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

    // ---- parsing (internal: the tests use them) ----

    internal fun camera(e: JsonElement, doorbell: Boolean): RingCamera? {
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
    internal fun battery(d: JsonObject): Int? {
        fun level(e: JsonElement?): Double? = (e as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
        val levels = listOfNotNull(level(d["battery_life"]), level(d["battery_life_2"]))
        val health = d["health"].obj()
        val none = health != null && listOf("battery_percentage", "battery_present", "second_battery_percentage").none { key ->
            (health[key] as? JsonPrimitive)?.let { it.booleanOrNull == true || (it.doubleOrNull ?: 0.0) != 0.0 } == true
        }
        return if (levels.isEmpty() || none) null else levels.min().toInt().coerceIn(0, 100)
    }

    internal fun event(e: JsonElement): DeviceEvent? {
        val d = e.obj() ?: return null
        val at = d.text("created_at")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
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
        val request = Request.Builder().url("${endpoints.oauth}/oauth/token")
            .header("2fa-support", "true").header("2fa-code", code.orEmpty())
            .header("hardware_id", hardwareId).header("User-Agent", USER_AGENT).header("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON)).build()
        return try {
            http.value.newBuilder().callTimeout(CALL_S, TimeUnit.SECONDS).build().newCall(request).await().use { r ->
                val text = r.body?.string().orEmpty()
                Raw.Done(r.code, runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap()))
            }
        } catch (e: IOException) {
            Raw.Failed("Ring can't be reached right now")
        }
    }

    private fun authHeaders(accessToken: String, hardwareId: String) = okhttp3.Headers.Builder()
        .add("authorization", "Bearer $accessToken").add("hardware_id", hardwareId).add("User-Agent", USER_AGENT).build()

    private suspend fun <T> get(url: String, accessToken: String, hardwareId: String, what: String, parse: (String) -> T): RingReply<T> {
        val request = Request.Builder().url(url).headers(authHeaders(accessToken, hardwareId)).header("Accept", "application/json").get().build()
        return call(request, hardwareId, what, parse)
    }

    private suspend fun <T> call(request: Request, hardwareId: String, what: String, parse: (String) -> T): RingReply<T> = try {
        http.value.newBuilder().callTimeout(CALL_S, TimeUnit.SECONDS).build().newCall(request).await().use { r ->
            val text = r.body?.string().orEmpty()
            when {
                r.code == 401 -> RingReply.Unauthorized
                r.code == 404 && hardwareId in text -> RingReply.NoSession
                r.code == 429 -> RingReply.Failed("Ring says to slow down; try again in a few minutes")
                !r.isSuccessful -> { Log.w(LogTag.Home, "Ring: HTTP ${r.code} for $what"); RingReply.Failed("Ring answered HTTP ${r.code} for $what") }
                else -> try { RingReply.Ok(parse(text)) } catch (e: SerializationException) { RingReply.Failed("Ring's answer for $what wasn't what Edith expected") }
            }
        }
    } catch (e: IOException) {
        RingReply.Failed("Ring can't be reached right now")
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
        private val JSON = "application/json".toMediaType()
    }
}

/** Runs the call without blocking a thread; cancelling the coroutine cancels the HTTP call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, r, _ -> r.close() }
    })
}

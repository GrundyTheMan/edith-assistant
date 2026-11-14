package com.edith.app.ring

import com.edith.app.testing.FakeClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import okhttp3.OkHttpClient
import java.util.Collections

/**
 * A pretend Ring: one server for its three hosts ([RingEndpoints] points them at paths on it). It rotates refresh
 * tokens the way Ring does: a refresh token works once, and each refresh hands out the next (`rt-1`, `rt-2`, …).
 */
class FakeRing {
    val server = MockWebServer()
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    /** The JSON bodies sent to the token endpoint, in order. */
    val tokenBodies: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    /** Refresh tokens Ring would still accept. */
    val validRefresh: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private var counter = 0
    @Volatile var password = "hunter2"
    /** null: no 2FA. Otherwise the code Ring wants (it answers 412 until the sign-in carries it). */
    @Volatile var wantCode: String? = null
    @Volatile var devicesJson = """{"doorbots":[],"stickup_cams":[],"authorized_doorbots":[],"chimes":[],"other":[]}"""
    @Volatile var eventsJson = """{"events":[]}"""
    /** What `video_search/history` answers. */
    @Volatile var clipsJson = """{"video_search":[]}"""
    /** The address `dings/{id}/recording` gives; null: Ring has nothing yet (404). */
    @Volatile var recordingUrl: String? = "https://clips.example.test/video.mp4"
    /** The address `dings/{id}/share/play` gives; null: 404. */
    @Volatile var sharePlayUrl: String? = "https://clips.example.test/shared.mp4"
    @Volatile var snapshotBytes: ByteArray? = byteArrayOf(1, 2, 3)
    @Volatile var snapshotCode = 200
    /** path → answer, for anything the tests want to override (e.g. `/clients_api/session`). */
    val overrides = Collections.synchronizedMap(mutableMapOf<String, (RecordedRequest) -> MockResponse>())
    /** Access tokens Ring accepts; everything else gets 401. */
    val validAccess: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    var sessionStarted = 0

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.url.encodedPath
                overrides[path]?.let { return it(request) }
                return when {
                    path == "/oauth/token" -> token(request)
                    !authorised(request) -> MockResponse.Builder().code(401).body("{}").build()
                    path == "/clients_api/session" -> { sessionStarted++; json("{}") }
                    path == "/clients_api/ring_devices" -> json(devicesJson)
                    path.endsWith("/events") -> json(eventsJson)
                    path == "/clients_api/video_search/history" -> json(clipsJson)
                    path.startsWith("/clients_api/dings/") -> (if (path.endsWith("/recording")) recordingUrl else sharePlayUrl)?.let { json("""{"url":"$it"}""") } ?: MockResponse.Builder().code(404).body("{}").build()
                    path.startsWith("/snapshots/next/") -> snapshot()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    private fun authorised(r: RecordedRequest) = r.headers["authorization"]?.removePrefix("Bearer ") in validAccess

    private fun json(text: String, code: Int = 200) =
        MockResponse.Builder().code(code).body(text).addHeader("Content-Type", "application/json").build()

    private fun snapshot(): MockResponse {
        val bytes = snapshotBytes ?: return MockResponse.Builder().code(snapshotCode).build()
        return MockResponse.Builder().code(snapshotCode).body(Buffer().write(bytes)).addHeader("Content-Type", "image/jpeg").build()
    }

    private fun token(r: RecordedRequest): MockResponse {
        val body = Json.parseToJsonElement(r.body?.utf8().orEmpty()).jsonObject
        tokenBodies += body
        return when (body["grant_type"]?.jsonPrimitive?.content) {
            "password" -> {
                val code = r.headers["2fa-code"].orEmpty()
                when {
                    body["password"]?.jsonPrimitive?.content != password -> json("""{"error":"invalid_grant"}""", 401)
                    wantCode != null && code.isEmpty() -> json("""{"error":"","tsv_state":"sms","phone":"+1xxxxxx89"}""", 412)
                    wantCode != null && code != wantCode -> json("""{"error":"Verification Code is invalid"}""", 400)
                    else -> issue()
                }
            }
            "refresh_token" -> {
                val rt = body["refresh_token"]?.jsonPrimitive?.content
                if (rt != null && validRefresh.remove(rt)) issue() else json("""{"error":"invalid_grant"}""", 401)
            }
            else -> json("{}", 400)
        }
    }

    /** A new access token and the next refresh token. */
    private fun issue(): MockResponse {
        counter++
        validRefresh += "rt-$counter"
        validAccess += "at-$counter"
        return json("""{"access_token":"at-$counter","refresh_token":"rt-$counter","expires_in":3600,"scope":"client","token_type":"Bearer"}""")
    }

    /** Pretend the owner signed in before: the refresh token `rt-0` is good. */
    fun signedInBefore() { validRefresh += "rt-0" }

    fun camera(id: Int, name: String, kind: String = "stickup_cam_v4", battery: String? = null, connection: String = "online", motion: Boolean = true, location: String = "loc-1") =
        """{"id":$id,"description":"$name","kind":"$kind","location_id":"$location","alerts":{"connection":"$connection"},""" +
            (if (battery != null) """"battery_life":$battery,""" else "") + """"settings":{"motion_detection_enabled":$motion}}"""

    fun event(kind: String, at: String, person: Boolean = false) =
        """{"created_at":"$at","kind":"$kind","ding_id_str":"d-$at","cv_properties":{"person_detected":${if (person) "true" else "null"},"detection_type":null}}"""

    val endpoints: RingEndpoints get() {
        val base = server.url("/").toString().trimEnd('/')
        return RingEndpoints(oauth = base, clients = "$base/clients_api", snapshots = base)
    }

    fun api() = RingApi(lazy { OkHttpClient() }, endpoints)
    fun count(path: String) = requests.count { it.url.encodedPath == path }
    fun shutdown() = server.close()

    companion object {
        fun clock() = FakeClock()
    }
}

/** One row of Ring's video search. [hadPlan] false: an event Ring did not keep (no Ring Protect). */
fun clipRowJson(id: String, createdAt: Long, kind: String = "motion", person: Boolean = false, hadPlan: Boolean = true, seconds: Int = 24, url: String? = null) =
    """{"ding_id":"$id","created_at":$createdAt,"kind":"$kind","had_subscription":$hadPlan,"duration":$seconds,""" + (if (url != null) """"lq_url":"$url",""" else "") +
        """"cv_properties":{"person_detected":${if (person) "true" else "null"},"detection_type":null}}"""

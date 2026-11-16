package com.edith.app.blink

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections

/**
 * A pretend Blink, shaped like what `blinkpy` talks to (read 2026-11-15): the OAuth host's sign-in page with a CSRF token in
 * its `oauth-args` script, the password and 2FA posts, the redirect that carries a code, the PKCE token exchange and the
 * rotating refresh token; and the account's server (under `/rest`) with tier info, the homescreen, snapshot commands and
 * the media list. Cookies are checked: the sign-in only works with the ones the earlier steps set.
 */
class FakeBlink {
    val server = MockWebServer()
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    /** The form bodies sent to /oauth/token, in order. */
    val tokenForms: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())
    val validRefresh: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    val validAccess: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private var counter = 0
    @Volatile private var challenge: String? = null
    @Volatile var email = "a@b.c"
    @Volatile var password = "hunter2"
    /** null: no 2FA. Otherwise the code Blink wants (it answers 412 to the password until the code is verified). */
    @Volatile var wantCode: String? = null
    @Volatile var signInPageHtml: String? = null
    @Volatile var homescreen = homescreenJson(cameras = listOf(camera("1", "Backyard", thumbnail = "/media/thumb/1")))
    @Volatile var mediaJson = """{"media":[]}"""
    /** Answers to the snapshot request, in order; the last repeats. */
    val snapshotAnswers: MutableList<MockResponse> = Collections.synchronizedList(mutableListOf())
    @Volatile var commandComplete = true
    /** When set, the camera's thumbnail changes to this after a snapshot has been asked for (what Blink does once the camera uploads). */
    @Volatile var thumbnailAfterSnapshot: String? = "/media/thumb/1-new"
    @Volatile private var snapshotAsked = false
    @Volatile var pictureBytes: ByteArray = byteArrayOf(9, 8, 7)
    /** Every Authorization header the account's server saw, for checking the bearer. */
    val bearers: MutableList<String> = Collections.synchronizedList(mutableListOf())

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.url.encodedPath
                if (path.startsWith("/rest")) return rest(request, path.removePrefix("/rest"))
                return when {
                    path == "/oauth/v2/authorize" -> authorize(request)
                    path == "/oauth/v2/signin" && request.method == "GET" -> signinPage()
                    path == "/oauth/v2/signin" -> signin(request)
                    path == "/oauth/v2/2fa/verify" -> verify(request)
                    path == "/oauth/token" -> token(request)
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    private fun json(text: String, code: Int = 200) = MockResponse.Builder().code(code).body(text).addHeader("Content-Type", "application/json").build()
    private fun form(r: RecordedRequest): Map<String, String> = r.body?.utf8().orEmpty().split('&').filter { it.isNotEmpty() }
        .associate { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
    private fun cookies(r: RecordedRequest) = r.headers["Cookie"].orEmpty()

    // ---- sign-in ----

    private fun authorize(r: RecordedRequest): MockResponse {
        if (r.url.queryParameter("code_challenge") != null) {
            challenge = r.url.queryParameter("code_challenge")
            return MockResponse.Builder().code(200).body("<html>sign in</html>").addHeader("Set-Cookie", "sess=s1; Path=/").build()
        }
        // The final authorize, after the password (and the code): only with both cookies, and it redirects to the app with a code.
        val signedIn = "auth=ok" in cookies(r)
        if (!signedIn || "sess=s1" !in cookies(r)) return MockResponse.Builder().code(401).build()
        return MockResponse.Builder().code(302).addHeader("Location", "immedia-blink://applinks.blink.com/signin/callback?code=C1&state=x").build()
    }

    private fun signinPage(): MockResponse {
        val html = signInPageHtml ?: """<html><body><script id="oauth-args" type="application/json">{"csrf-token":"csrf-1","other":1}</script></body></html>"""
        return MockResponse.Builder().code(200).body(html).addHeader("Content-Type", "text/html").build()
    }

    private fun signin(r: RecordedRequest): MockResponse {
        val f = form(r)
        if (f["csrf-token"] != "csrf-1" || "sess=s1" !in cookies(r)) return MockResponse.Builder().code(403).build()
        if (f["username"] != email || f["password"] != password) return MockResponse.Builder().code(401).body("""{"error":"bad"}""").build()
        if (wantCode != null) return MockResponse.Builder().code(412).body("{}").build()
        return MockResponse.Builder().code(302).addHeader("Location", "/oauth/v2/authorize").addHeader("Set-Cookie", "auth=ok; Path=/").build()
    }

    private fun verify(r: RecordedRequest): MockResponse {
        val f = form(r)
        if (f["csrf-token"] != "csrf-1" || "sess=s1" !in cookies(r)) return MockResponse.Builder().code(403).build()
        if (f["2fa_code"] != wantCode) return MockResponse.Builder().code(401).body("""{"status":"invalid"}""").build()
        return MockResponse.Builder().code(201).body("""{"status":"auth-completed"}""").addHeader("Content-Type", "application/json").addHeader("Set-Cookie", "auth=ok; Path=/").build()
    }

    private fun token(r: RecordedRequest): MockResponse {
        val f = form(r)
        tokenForms += f
        return when (f["grant_type"]) {
            "authorization_code" -> {
                val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(f["code_verifier"].orEmpty().toByteArray()))
                if (f["code"] == "C1" && expected == challenge && f["client_id"] == "ios") issue() else json("""{"error":"invalid_grant"}""", 400)
            }
            "refresh_token" -> if (f["refresh_token"]?.let { validRefresh.remove(it) } == true) issue() else json("""{"error":"invalid_grant"}""", 400)
            else -> json("{}", 400)
        }
    }

    private fun issue(): MockResponse {
        counter++
        validRefresh += "rt-$counter"; validAccess += "at-$counter"
        return json("""{"access_token":"at-$counter","refresh_token":"rt-$counter","expires_in":3600,"token_type":"Bearer"}""")
    }

    fun signedInBefore() { validRefresh += "rt-0" }

    // ---- the account ----

    private fun rest(r: RecordedRequest, path: String): MockResponse {
        val bearer = r.headers["Authorization"].orEmpty().removePrefix("Bearer ")
        bearers += bearer
        if (bearer !in validAccess) return MockResponse.Builder().code(401).body("{}").build()
        return when {
            path == "/api/v1/users/tier_info" -> json("""{"tier":"u014","account_id":4242}""")
            path == "/api/v3/accounts/4242/homescreen" -> json(if (snapshotAsked && thumbnailAfterSnapshot != null) homescreen.replace("/media/thumb/1\"", "$thumbnailAfterSnapshot\"") else homescreen)
            r.method == "POST" && path.endsWith("/thumbnail") -> { snapshotAsked = true; if (snapshotAnswers.isEmpty()) json("""{"id":77,"network_id":5}""") else snapshotAnswers.let { if (it.size > 1) it.removeAt(0) else it[0] } }
            path == "/network/5/command/77" -> json("""{"complete":$commandComplete,"status_code":908}""")
            path.startsWith("/media/thumb/") || path.contains("/thumbnail/") -> MockResponse.Builder().code(200).body(Buffer().write(pictureBytes)).addHeader("Content-Type", "image/jpeg").build()
            path == "/api/v1/accounts/4242/media/changed" -> json(mediaJson)
            path.startsWith("/api/v2/accounts/4242/media/clip/") -> MockResponse.Builder().code(200).body(Buffer().write(byteArrayOf(1, 2))).addHeader("Content-Type", "video/mp4").build()
            else -> MockResponse.Builder().code(404).build()
        }
    }

    fun count(path: String) = requests.count { it.url.encodedPath == path }

    val endpoints: BlinkEndpoints get() {
        val base = server.url("/").toString().trimEnd('/')
        return BlinkEndpoints(oauth = base, tierInfo = "$base/rest/api/v1/users/tier_info", rest = { "$base/rest" })
    }

    fun api() = BlinkApi(lazy { OkHttpClient() }, endpoints)
    fun shutdown() = server.close()

    companion object {
        fun camera(id: String, name: String, network: String = "5", status: String = "done", battery: String = "ok", thumbnail: String? = null, type: String = "catalina") =
            """{"id":$id,"name":"$name","network_id":$network,"status":"$status","battery":"$battery","type":"$type"${if (thumbnail != null) ""","thumbnail":"$thumbnail"""" else ""}}"""

        fun homescreenJson(cameras: List<String> = emptyList(), owls: List<String> = emptyList(), doorbells: List<String> = emptyList(), syncOffline: Boolean = false) =
            """{"networks":[{"id":5,"armed":true}],"sync_modules":[{"network_id":5,"status":"${if (syncOffline) "offline" else "online"}"}],"cameras":[${cameras.joinToString()}],"owls":[${owls.joinToString()}],"doorbells":[${doorbells.joinToString()}]}"""

        fun media(id: String, deviceId: String, name: String, at: String, source: String = "pir", path: String = "/api/v2/accounts/4242/media/clip/$id.mp4", deleted: Boolean = false) =
            """{"id":$id,"device_id":$deviceId,"device_name":"$name","created_at":"$at","source":"$source","media":"$path","thumbnail":"/media/thumb/$id","deleted":$deleted}"""
    }
}

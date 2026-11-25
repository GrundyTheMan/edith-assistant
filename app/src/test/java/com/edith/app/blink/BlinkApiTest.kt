package com.edith.app.blink

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** C29: the sign-in steps against a pretend Blink that follows blinkpy, and what each answer means. */
class BlinkApiTest {
    private val blink = FakeBlink()
    @After fun tearDown() = blink.shutdown()
    private val hw = "5D1A2B3C-0000-4000-8000-000000000001"

    private suspend fun started() = (blink.api().begin(hw) as BlinkStart.Ok).session

    @Test fun `a sign-in without a code goes through the page, the password, the redirect and the PKCE exchange`() = runBlocking {
        val s = started()
        val r = blink.api().signIn(s, "a@b.c", "hunter2")
        assertTrue(r.toString(), r is BlinkLogin.Ok)
        assertEquals("at-1", (r as BlinkLogin.Ok).tokens.accessToken)
        val form = blink.tokenForms.single()
        assertEquals("authorization_code", form["grant_type"])
        assertEquals(hw, form["hardware_id"])
        assertEquals("ios", form["client_id"])
        assertEquals("immedia-blink://applinks.blink.com/signin/callback", form["redirect_uri"])
        // The pretend Blink checks the verifier against the challenge it was given, so a wrong one would not have got tokens.
        assertEquals("the sign-in page is loaded first", listOf("/oauth/v2/authorize", "/oauth/v2/signin"), blink.requests.take(2).map { it.url.encodedPath })
    }

    @Test fun `Blink asks for a code, a wrong one is refused, the right one finishes it`() = runBlocking {
        blink.wantCode = "123456"
        val api = blink.api()
        val s = started()
        assertEquals(BlinkLogin.NeedsCode, api.signIn(s, "a@b.c", "hunter2"))
        assertEquals(BlinkLogin.WrongCode, api.verify(s, "000000"))
        assertTrue("the same sign-in still takes the right code", api.verify(s, "123456") is BlinkLogin.Ok)
        assertEquals("no tokens before the code", 1, blink.tokenForms.size)
    }

    @Test fun `a wrong password is a plain refusal`() = runBlocking {
        assertEquals(BlinkLogin.BadLogin, blink.api().signIn(started(), "a@b.c", "nope"))
        assertTrue(blink.tokenForms.isEmpty())
    }

    @Test fun `a sign-in page without the token means Blink changed it, and says so`() = runBlocking {
        blink.signInPageHtml = "<html>new design</html>"
        val r = blink.api().begin(hw)
        assertTrue(r is BlinkStart.Failed && "changed" in r.why)
    }

    @Test fun `a sign-in page Blink refuses says which step, who answered and what the page said, and sends a language`() = runBlocking {
        val real = blink.server.dispatcher
        var language: String? = null
        for (refusedPath in listOf("/oauth/v2/authorize" to "the first step", "/oauth/v2/signin" to "the second step")) {
            blink.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.url.encodedPath == refusedPath.first) {
                        language = request.headers["Accept-Language"]
                        return MockResponse.Builder().code(406).addHeader("Server", "AkamaiGHost").body("<html><head><style>x{}</style></head><body><h1>Not   Acceptable</h1><p>Blocked by policy</p></body></html>").build()
                    }
                    return real.dispatch(request)
                }
            }
            val r = blink.api().begin(hw)
            assertTrue(r.toString(), r is BlinkStart.Failed)
            val why = (r as BlinkStart.Failed).why
            assertTrue(why, "HTTP 406" in why && refusedPath.second in why && "AkamaiGHost" in why && "Not Acceptable Blocked by policy" in why)
            assertTrue("no markup or style in the excerpt: $why", '<' !in why && "x{}" !in why)
        }
        assertEquals("en-US,en;q=0.9", language)
    }

    /** The pretend Blink with the sign-in's first page answering [statuses] in turn (the last repeats) and counting the asks. */
    private fun answeringAuthorize(vararg statuses: Int): () -> Int {
        val real = blink.server.dispatcher
        var asked = 0
        blink.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/oauth/v2/authorize" && request.url.queryParameter("code_challenge") != null) {
                    val status = statuses[minOf(asked++, statuses.size - 1)]
                    if (status != 200) return MockResponse.Builder().code(status).body("Not Acceptable").build()
                }
                return real.dispatch(request)
            }
        }
        return { asked }
    }

    @Test fun `a 406 from Blink is tried again over IPv4, and when that works IPv4 is used from then on`() = runBlocking {
        val asked = answeringAuthorize(406, 200)
        val api = blink.api()
        assertTrue(api.begin(hw) is BlinkStart.Ok)
        assertEquals(2, asked())
        assertTrue(api.ipv4Only)
        val again = api.begin(hw)
        assertTrue(again is BlinkStart.Ok)
        assertEquals("the next sign-in goes straight over IPv4, with no refused try first", 3, asked())
    }

    @Test fun `a 406 on both routes says so, with both answers, and does not loop`() = runBlocking {
        val asked = answeringAuthorize(406)
        val api = blink.api()
        val r = api.begin(hw)
        assertTrue(r.toString(), r is BlinkStart.Failed)
        r as BlinkStart.Failed
        assertEquals(406, r.status)
        assertTrue(r.why, "Over IPv4 only: answered HTTP 406" in r.why)
        assertEquals("one try on each route and no more", 2, asked())
        assertTrue(!api.ipv4Only)
    }

    @Test fun `a refusal other than 406 is not retried`() = runBlocking {
        val asked = answeringAuthorize(500)
        val r = blink.api().begin(hw)
        assertTrue(r is BlinkStart.Failed && r.status == 500)
        assertEquals(1, asked())
    }

    @Test fun `the IPv4-only lookup keeps IPv4 addresses and fails for a host that has none`() {
        assertTrue(BlinkIpv4.ONLY.lookup("localhost").all { it is java.net.Inet4Address })
        assertTrue(runCatching { BlinkIpv4.ONLY.lookup("::1") }.exceptionOrNull() is java.net.UnknownHostException)
    }

    @Test fun `a 202 that lists code methods also asks for the code`() = runBlocking {
        val real = blink.server.dispatcher
        blink.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "POST" && request.url.encodedPath == "/oauth/v2/signin") MockResponse.Builder().code(202).body("""{"tsv_state":"sms","next_time_in_secs":30}""").build()
                else real.dispatch(request)
        }
        assertEquals(BlinkLogin.NeedsCode, blink.api().signIn(started(), "a@b.c", "hunter2"))
    }

    @Test fun `the refresh token is traded for new ones and an old one stops working`() = runBlocking {
        blink.signedInBefore()
        val api = blink.api()
        val first = api.refresh("rt-0", hw) as BlinkRefresh.Ok
        assertEquals("rt-1", first.tokens.refreshToken)
        assertEquals(BlinkRefresh.Rejected, api.refresh("rt-0", hw))
        assertEquals(hw, blink.tokenForms.first()["hardware_id"])
    }

    @Test fun `the tier and the account come from tier_info`() = runBlocking {
        blink.signedInBefore()
        val api = blink.api()
        val t = (api.refresh("rt-0", hw) as BlinkRefresh.Ok).tokens
        assertEquals(BlinkTier("u014", "4242"), (api.tier(t.accessToken) as BlinkReply.Ok).value)
        assertEquals(BlinkReply.Unauthorized, api.tier("bogus"))
    }

    @Test fun `a busy sync module is Busy, an offline one is a failure that says what to check`() = runBlocking {
        blink.signedInBefore()
        val api = blink.api()
        val token = (api.refresh("rt-0", hw) as BlinkRefresh.Ok).tokens.accessToken
        val where = BlinkTier("u014", "4242")
        val camera = BlinkCamera("1", "5", "Backyard", BlinkKind.Camera, "catalina", null, null, null)
        blink.snapshotAnswers += MockResponse.Builder().code(409).body("""{"message":"System is busy, please wait"}""").build()
        assertTrue(api.requestSnapshot(token, where, camera) is BlinkReply.Busy)
        blink.snapshotAnswers.clear()
        blink.snapshotAnswers += MockResponse.Builder().code(409).body("""{"message":"Sync module is offline"}""").build()
        val r = api.requestSnapshot(token, where, camera)
        assertTrue(r is BlinkReply.Failed && "plugged in" in r.why)
    }
}

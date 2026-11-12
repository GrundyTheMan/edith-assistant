package com.edith.app.google.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import com.edith.app.core.log.LogRingBuffer
import com.edith.app.google.GoogleAuthorizer
import com.edith.app.google.GoogleScope
import com.edith.app.google.GoogleSignIn
import com.edith.app.testing.FakeClock
import com.edith.app.testing.InMemorySecretStore
import com.edith.app.testing.testDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.URLDecoder
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** C17b: browser sign-in end to end (loopback, paste, cancel, timeout) and access through the refresh token. */
class BrowserGoogleTest {
    private val server = MockWebServer()
    private val dispatchers = testDispatchers().copy(io = Dispatchers.IO)
    private val secrets = InMemorySecretStore()
    private val clock = FakeClock()
    private val opened = mutableListOf<String>()
    private var noBrowser = false

    @Before fun setUp() {
        server.start()
        LogRingBuffer.clear()
    }

    @After fun tearDown() = server.close()

    private fun oauth() = BrowserOAuth(
        lazy { OkHttpClient() }, dispatchers, clientId = "desktop.apps.googleusercontent.com",
        tokenUrl = server.url("/token").toString(), revokeUrl = server.url("/revoke").toString(),
    )

    private fun browser(timeout: kotlin.time.Duration = 1.minutes, visible: StateFlow<Boolean>? = null) = BrowserGoogle(
        oauth(), secrets, clock, dispatchers, backLink = "com.edith.app.debug://signed-in",
        knownEmail = { "owner@gmail.com" },
        openBrowser = { _, url -> if (noBrowser) throw ActivityNotFoundException() else synchronized(opened) { opened += url } },
        loopbackTimeout = timeout,
        appVisible = visible,
    )

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    /** Starts a sign-in and waits until "the browser" was opened; returns the sign-in and Google's page address. */
    private suspend fun CoroutineScope.startSignIn(b: BrowserGoogle): Pair<Deferred<GoogleSignIn.Result>, okhttp3.HttpUrl> {
        val before = synchronized(opened) { opened.size }
        val result = async(Dispatchers.IO) { b.signIn(Activity(), "") }
        withTimeout(5_000) { while (synchronized(opened) { opened.size } == before) delay(10) }
        return result to synchronized(opened) { opened.last() }.toHttpUrl()
    }

    /** What Chrome does after Google's page: a GET to the redirect address. */
    private fun browserReturns(redirect: String, query: String) {
        val url = redirect.toHttpUrl()
        Socket(InetAddress.getByName("127.0.0.1"), url.port).use { s ->
            s.getOutputStream().write("GET /?$query HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            s.getInputStream().readBytes()
        }
    }

    private fun form(body: String): Map<String, String> = body.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    @Test fun `a full sign-in through the loopback stores the refresh token and caches the access token`() = runBlocking {
        server.enqueue(json(BrowserOAuthTest.tokens(access = "ya29.first-access", refresh = "1//the-refresh-token")))
        val b = browser()
        val (result, page) = startSignIn(b)
        assertEquals("owner@gmail.com", page.queryParameter("login_hint"))
        assertTrue(b.waiting.value)
        assertNotNull("pending sign-in kept until the exchange", secrets.get(BrowserGoogle.PENDING_KEY))
        val redirect = page.queryParameter("redirect_uri")!!
        val state = page.queryParameter("state")!!
        browserReturns(redirect, "state=$state&code=4/0the-code&scope=email")

        assertEquals(GoogleSignIn.Result.SignedIn("owner@gmail.com", null), result.await())
        assertFalse(b.waiting.value)
        assertEquals("1//the-refresh-token", secrets.get(BrowserGoogle.REFRESH_TOKEN_KEY))
        assertNull("pending deleted", secrets.get(BrowserGoogle.PENDING_KEY))
        val exchange = form(server.takeRequest().body!!.utf8())
        assertEquals("4/0the-code", exchange["code"])
        assertEquals(redirect, exchange["redirect_uri"])
        assertEquals(page.queryParameter("code_challenge"), BrowserOAuth.challengeFor(exchange["code_verifier"]!!))

        assertEquals("cached, no refresh", GoogleAuthorizer.Result.Granted("ya29.first-access"), b.authorize(GoogleScope.entries.toSet()))
        assertEquals(1, server.requestCount)

        // §6: none of the secrets reached the log.
        val log = LogRingBuffer.tail(LogRingBuffer.CAPACITY).joinToString("\n")
        listOf("4/0the-code", state, exchange["code_verifier"]!!, "ya29.first-access", "1//the-refresh-token", redirect, "signature-not-checked")
            .forEach { assertFalse("'$it' in log: $log", log.contains(it)) }
        assertTrue(log.contains("Browser sign-in: signed in"))
    }

    @Test fun `a code that arrives while Edith is off screen is exchanged once she's back`() = runBlocking {
        server.enqueue(json(BrowserOAuthTest.tokens()))
        val visible = MutableStateFlow(false)   // the browser is in front: Android blocks Edith's network
        val b = browser(visible = visible)
        val (result, page) = startSignIn(b)
        browserReturns(page.queryParameter("redirect_uri")!!, "state=${page.queryParameter("state")}&code=4/held")
        delay(300)
        assertEquals("nothing sent while off screen", 0, server.requestCount)
        assertTrue("still waiting", b.waiting.value)
        visible.value = true   // the owner taps Back to Edith
        assertEquals(GoogleSignIn.Result.SignedIn("owner@gmail.com", null), result.await())
        assertEquals("4/held", form(server.takeRequest().body!!.utf8())["code"])
    }

    @Test fun `a pasted address signs in, and a wrong one is refused`() = runBlocking {
        server.enqueue(json(BrowserOAuthTest.tokens()))
        val b = browser()
        val (result, page) = startSignIn(b)
        b.paste("  ${page.queryParameter("redirect_uri")!!.removePrefix("http://")}/?state=${page.queryParameter("state")}&code=4/pasted ")
        assertEquals(GoogleSignIn.Result.SignedIn("owner@gmail.com", null), result.await())
        assertEquals("4/pasted", form(server.takeRequest().body!!.utf8())["code"])

        val (again, _) = startSignIn(b)
        b.paste("http://127.0.0.1:1234/?state=an-old-state&code=4/old")
        assertEquals(GoogleSignIn.Result.Failed(BrowserGoogle.WRONG_ADDRESS), again.await())
        assertNull(secrets.get(BrowserGoogle.PENDING_KEY))
    }

    @Test fun `declining, cancelling and timing out leave the owner signed out`() = runBlocking {
        val b = browser()
        val (declined, page) = startSignIn(b)
        browserReturns(page.queryParameter("redirect_uri")!!, "error=access_denied&state=${page.queryParameter("state")}")
        assertEquals(GoogleSignIn.Result.Failed(BrowserGoogle.DECLINED), declined.await())

        val (cancelled, _) = startSignIn(b)
        b.cancel()
        assertEquals(GoogleSignIn.Result.Cancelled, cancelled.await())
        assertNull(secrets.get(BrowserGoogle.PENDING_KEY))

        val (timedOut, _) = startSignIn(browser(timeout = 300.milliseconds))
        assertEquals(GoogleSignIn.Result.Failed(BrowserGoogle.TIMED_OUT), timedOut.await())
        assertNull(secrets.get(BrowserGoogle.PENDING_KEY))
        assertNull(secrets.get(BrowserGoogle.REFRESH_TOKEN_KEY))
        assertEquals(0, server.requestCount)
    }

    @Test fun `no browser on the device says so`() = runBlocking {
        noBrowser = true
        assertEquals(GoogleSignIn.Result.Failed(BrowserGoogle.NO_BROWSER), browser().signIn(Activity(), ""))
        assertNull(secrets.get(BrowserGoogle.PENDING_KEY))
    }

    @Test fun `Google rejecting the client points at the Desktop client ID`() = runBlocking {
        server.enqueue(json("""{"error":"invalid_client"}""", 401))
        val b = browser()
        val (result, page) = startSignIn(b)
        b.paste("${page.queryParameter("redirect_uri")}/?state=${page.queryParameter("state")}&code=c")
        assertEquals(GoogleSignIn.Result.Failed(BrowserGoogle.BAD_CLIENT), result.await())
    }

    @Test fun `a sign-in left pending by a killed process can still be finished`() = runBlocking {
        val first = browser()
        val (killed, page) = startSignIn(first)
        killed.cancel()   // the process dies while the owner is in the browser
        runCatching { killed.await() }
        assertNotNull(secrets.get(BrowserGoogle.PENDING_KEY))

        server.enqueue(json(BrowserOAuthTest.tokens()))
        val second = browser()   // the next run of Edith
        clock.advance(Duration.ofMinutes(3))
        val resumed = async(Dispatchers.IO) { second.resumePending() }
        withTimeout(5_000) { while (!second.waiting.value) delay(10) }
        val redirect = page.queryParameter("redirect_uri")!!
        second.paste("$redirect/?state=${page.queryParameter("state")}&code=4/late")
        assertEquals(GoogleSignIn.Result.SignedIn("owner@gmail.com", null), resumed.await())
        assertEquals("the redirect from the first run", redirect, form(server.takeRequest().body!!.utf8())["redirect_uri"])
    }

    @Test fun `a pending sign-in older than 10 minutes is dropped`() = runBlocking {
        val (killed, _) = startSignIn(browser())
        killed.cancel()
        runCatching { killed.await() }
        clock.advance(Duration.ofMinutes(11))
        assertNull(browser().resumePending())
        assertNull(secrets.get(BrowserGoogle.PENDING_KEY))
    }

    @Test fun `authorize refreshes an expired token`() = runBlocking {
        secrets.put(BrowserGoogle.REFRESH_TOKEN_KEY, "1//stored")
        server.enqueue(json("""{"access_token":"ya29.one","expires_in":3599,"scope":"${GoogleScope.entries.joinToString(" ") { it.uri }}"}"""))
        server.enqueue(json("""{"access_token":"ya29.two","expires_in":3599,"scope":"${GoogleScope.entries.joinToString(" ") { it.uri }}"}"""))
        val b = browser()
        assertEquals(GoogleAuthorizer.Result.Granted("ya29.one"), b.authorize(setOf(GoogleScope.CalendarEvents)))
        assertEquals(GoogleAuthorizer.Result.Granted("ya29.one"), b.authorize(setOf(GoogleScope.GmailReadonly)))
        clock.advance(Duration.ofSeconds(3599 - 30))   // under a minute left
        assertEquals(GoogleAuthorizer.Result.Granted("ya29.two"), b.authorize(setOf(GoogleScope.CalendarEvents)))
        assertEquals(2, server.requestCount)
        assertEquals("1//stored", form(server.takeRequest().body!!.utf8())["refresh_token"])
    }

    @Test fun `two callers at once share one refresh`() = runBlocking {
        secrets.put(BrowserGoogle.REFRESH_TOKEN_KEY, "1//stored")
        server.enqueue(json("""{"access_token":"ya29.shared","expires_in":3599}"""))
        val b = browser()
        val results = List(5) { async(Dispatchers.IO) { b.authorize(setOf(GoogleScope.CalendarEvents)) } }.awaitAll()
        assertTrue(results.all { it == GoogleAuthorizer.Result.Granted("ya29.shared") })
        assertEquals(1, server.requestCount)
    }

    @Test fun `fewer scopes, an expired grant, no token and no network`() = runBlocking {
        val b = browser()
        assertEquals("no refresh token", GoogleAuthorizer.Result.NeedsConsent(null), b.authorize(setOf(GoogleScope.CalendarEvents)))

        secrets.put(BrowserGoogle.REFRESH_TOKEN_KEY, "1//stored")
        server.enqueue(json("""{"access_token":"ya29.partial","expires_in":3599,"scope":"${GoogleScope.CalendarEvents.uri}"}"""))
        assertEquals("Gmail unticked on Google's page", GoogleAuthorizer.Result.NeedsConsent(null), b.authorize(setOf(GoogleScope.GmailReadonly)))
        assertEquals("1//stored", secrets.get(BrowserGoogle.REFRESH_TOKEN_KEY))

        server.enqueue(json("""{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""", 400))
        b.clearToken("ya29.partial")
        assertEquals(GoogleAuthorizer.Result.NeedsConsent(null), b.authorize(setOf(GoogleScope.CalendarEvents)))
        assertNull("an expired grant is deleted", secrets.get(BrowserGoogle.REFRESH_TOKEN_KEY))

        secrets.put(BrowserGoogle.REFRESH_TOKEN_KEY, "1//stored")
        server.close()
        assertEquals(GoogleAuthorizer.Result.Failed(BrowserGoogle.NO_NETWORK), b.authorize(setOf(GoogleScope.CalendarEvents)))
        assertEquals("kept when Google can't be reached", "1//stored", secrets.get(BrowserGoogle.REFRESH_TOKEN_KEY))
    }

    @Test fun `revoke tells Google and forgets the token`() = runBlocking {
        secrets.put(BrowserGoogle.REFRESH_TOKEN_KEY, "1//stored")
        server.enqueue(MockResponse.Builder().code(200).build())
        val b = browser()
        b.revoke("owner@gmail.com")
        assertEquals(mapOf("token" to "1//stored"), form(server.takeRequest().body!!.utf8()))
        assertNull(secrets.get(BrowserGoogle.REFRESH_TOKEN_KEY))
        assertFalse(b.hasRefreshToken())
    }
}

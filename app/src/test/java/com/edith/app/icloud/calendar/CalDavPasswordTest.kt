package com.edith.app.icloud.calendar

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.edith.app.testing.AppDispatchersForTests
import kotlinx.coroutines.runBlocking

/**
 * C24 §3: the iCloud app-specific password goes only to Apple's own hosts over TLS. Cleartext is allowed app-wide
 * (01-architecture §9), so the rule is [CalDavClient.carriesPassword], checked on every request, redirects included.
 */
class CalDavPasswordTest {
    private val apple = CalDavClient.BASE_URL.toHttpUrl()
    private fun sends(url: String, base: String = CalDavClient.BASE_URL) = CalDavClient.carriesPassword(url.toHttpUrl(), base.toHttpUrl())

    @Test fun `Apple's own hosts over https get the password`() {
        assertTrue(sends("https://caldav.icloud.com/"))
        assertTrue(sends("https://p01-caldav.icloud.com/123/calendars/"))
        assertTrue(sends("https://p42-caldav.icloud.com:443/abc/"))
        assertTrue(sends("https://icloud.com/"))
    }

    @Test fun `an address Apple names with http never gets the password`() {
        assertFalse(sends("http://p01-caldav.icloud.com/123/calendars/"))
        assertFalse(sends("http://caldav.icloud.com/"))
        assertFalse(sends("http://icloud.com/"))
    }

    @Test fun `lookalike hosts and user info never get it`() {
        assertFalse(sends("https://evilicloud.com/"))
        assertFalse(sends("https://icloud.com.evil.example/"))
        assertFalse(sends("https://caldav.icloud.com.evil.example/"))
        assertFalse(sends("https://caldav.icloud.com@evil.example/"))
        assertFalse(sends("https://evil.example/caldav.icloud.com/"))
        assertEquals("evil.example", "https://caldav.icloud.com@evil.example/".toHttpUrl().host)
    }

    @Test fun `a stand-in server gets it only over its own scheme`() {
        val base = "http://127.0.0.1:5555/"
        assertTrue(sends("http://127.0.0.1:5555/", base))
        assertTrue(sends("http://127.0.0.1:6666/partition/", base))   // same host: the stand-in's partition server
        assertFalse(sends("https://127.0.0.1:5555/", base))           // another scheme: not the origin it was given
        assertFalse(sends("http://localhost:5555/", base))
    }

    @Test fun `the real base address is trusted over https only`() {
        assertEquals("https", apple.scheme)
        assertFalse(sends("http://caldav.icloud.com/", CalDavClient.BASE_URL))
    }

    @Test fun `a redirect to a host that may not have it arrives without an Authorization header`() = runBlocking {
        val stranger = MockWebServer().apply { start() }
        val main = MockWebServer().apply { start() }
        try {
            // Both stand-ins are on this machine, so "another host" is the other name for it: the stand-in is reached
            // as `main.hostName` (usually `localhost`), and the stranger by `127.0.0.1` (or the reverse).
            val otherName = if (main.hostName == "127.0.0.1") "localhost" else "127.0.0.1"
            stranger.enqueue(MockResponse.Builder().code(207).body("<d:multistatus xmlns:d=\"DAV:\"/>").build())
            main.enqueue(MockResponse.Builder().code(301).addHeader("Location", "http://$otherName:${stranger.port}/x/").build())
            val client = CalDavClient(
                lazy { OkHttpClient() }, AppDispatchersForTests.unconfined, "owner@icloud.com", "abcd-efgh-ijkl-mnop",
                main.url("/").toString(),
            )
            client.get(io.ktor.http.Url(main.url("/start").toString()))
            val first = main.takeRequest()
            assertEquals(Credentials.basic("owner@icloud.com", "abcd-efgh-ijkl-mnop", Charsets.UTF_8), first.headers["Authorization"])
            val second = stranger.takeRequest()
            assertNull("the password followed a redirect to another host", second.headers["Authorization"])
        } finally {
            main.close()
            stranger.close()
        }
    }
}

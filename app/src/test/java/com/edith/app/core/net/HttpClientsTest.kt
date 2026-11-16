package com.edith.app.core.net

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit

/**
 * C37: the `secure` client can't make a cleartext request, whatever it's told to follow, and the `plain` client can.
 * A plain-`http` MockWebServer stands in for a cleartext address and a TLS one (a throwaway self-signed certificate that
 * the test client trusts) for Apple, Google, Meross and the rest.
 */
class HttpClientsTest {
    private val plainServer = MockWebServer()
    private val tlsServer = MockWebServer()

    /** What the test client trusts: the TLS server's own certificate, nothing else. */
    private lateinit var base: OkHttpClient

    @Before fun setUp() {
        // The names MockWebServer's URLs use: its host name can't be asked before start(), and useHttps must come before
        // it. `localhost` is what the URLs say, and the machine's canonical name for it varies, so both are covered.
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName(InetAddress.getByName("localhost").canonicalHostName)
            .build()
        val server = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val client = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        tlsServer.useHttps(server.sslSocketFactory())
        plainServer.start()
        tlsServer.start()
        base = OkHttpClient.Builder()
            .readTimeout(77, TimeUnit.SECONDS)
            .sslSocketFactory(client.sslSocketFactory(), client.trustManager)
            .build()
    }

    @After fun tearDown() {
        plainServer.close()
        tlsServer.close()
    }

    private fun get(client: OkHttpClient, url: okhttp3.HttpUrl) = client.newCall(Request.Builder().url(url).build()).execute()
    private fun ok(body: String = "ok") = MockResponse.Builder().code(200).body(body).build()

    @Test fun `secure refuses a plain http address and the server never sees a request`() {
        plainServer.enqueue(ok())
        val clients = HttpClients { base }
        try {
            get(clients.secure.value, plainServer.url("/")).close()
            fail("a cleartext request went out through the secure client")
        } catch (e: IOException) {
            assertTrue("was ${e::class.simpleName}: ${e.message}", e is UnknownServiceException)   // thrown at connect time, not by newCall
        }
        assertEquals(0, plainServer.requestCount)
    }

    @Test fun `plain talks to a plain http address`() {
        plainServer.enqueue(ok("hello"))
        get(HttpClients { base }.plain.value, plainServer.url("/")).use { assertEquals("hello", it.body.string()) }
        assertEquals(1, plainServer.requestCount)
    }

    @Test fun `secure talks to a TLS address`() {
        tlsServer.enqueue(ok("secret"))
        get(HttpClients { base }.secure.value, tlsServer.url("/")).use {
            assertEquals("secret", it.body.string())
            assertTrue(it.request.url.isHttps)
        }
    }

    @Test fun `secure doesn't follow an https to http redirect but plain does`() {
        val target = plainServer.url("/downgraded")
        plainServer.enqueue(ok("cleartext"))
        tlsServer.enqueue(MockResponse.Builder().code(302).addHeader("Location", target.toString()).build())
        get(HttpClients { base }.secure.value, tlsServer.url("/start")).use {
            assertEquals("the redirect comes back as an ordinary answer", 302, it.code)
        }
        assertEquals("nothing was sent to the cleartext address", 0, plainServer.requestCount)

        // The control: the plain client follows the same redirect, so the test above isn't passing by accident.
        tlsServer.enqueue(MockResponse.Builder().code(302).addHeader("Location", target.toString()).build())
        get(HttpClients { base }.plain.value, tlsServer.url("/start")).use { assertEquals("cleartext", it.body.string()) }
        assertEquals(1, plainServer.requestCount)
    }

    @Test fun `secure speaks only modern TLS`() {
        assertEquals(listOf(ConnectionSpec.MODERN_TLS), HttpClients { base }.secure.value.connectionSpecs)
    }

    @Test fun `the two clients share one pool, one dispatcher and the base settings`() {
        val clients = HttpClients { base }
        val plain = clients.plain.value
        val secure = clients.secure.value
        assertSame(plain.connectionPool, secure.connectionPool)
        assertSame(plain.dispatcher, secure.dispatcher)
        assertSame(plain.socketFactory, secure.socketFactory)
        assertEquals(77_000, plain.readTimeoutMillis)
        assertEquals(77_000, secure.readTimeoutMillis)
    }

    @Test fun `the base client is built once, and only when a client is first used`() {
        var built = 0
        val clients = HttpClients { built++; OkHttpClient() }
        assertEquals(0, built)
        clients.secure.value
        assertEquals(1, built)
        clients.plain.value
        assertEquals("plain and secure come from the same base", 1, built)
    }
}

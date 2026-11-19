package com.edith.app.printer.feed

import com.edith.app.accounts.AccountSecrets
import com.edith.app.accounts.Accounts
import com.edith.app.core.net.HttpClients
import com.edith.app.jarvis.FeedResult
import com.edith.app.jarvis.JarvisAccounts
import com.edith.app.jarvis.JarvisApi
import com.edith.app.jarvis.JarvisLink
import com.edith.app.jarvis.JarvisPairing
import com.edith.app.jarvis.feedJson
import com.edith.app.testing.AccountsHarness
import com.edith.app.testing.InMemorySecretStore
import com.edith.app.testing.testDispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * C39 E3: what the app can do about the printer, decided from a real Jarvis connection (paired through `Accounts`, as in the app)
 * against a TLS mock Jarvis. The point: "Jarvis can't be reached" must never be mistaken for "the printer isn't shared".
 */
class PrinterFeedAccessTest {
    private val server = MockWebServer()
    private val secrets = InMemorySecretStore()
    private lateinit var fingerprint: String
    private lateinit var accounts: Accounts
    private lateinit var feed: PrinterFeed

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
    private fun fixture(name: String) = checkNotNull(javaClass.getResourceAsStream("/printer-feed/$name.json")).bufferedReader().readText()
    private val sharedWithoutPrinter = """{"pcName":"BEN-PC","mail":[{"id":"m","label":"Work","email":"me@corp.com"}],"calendars":[],"tasks":[]}"""

    @Before fun setUp() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName(InetAddress.getByName("localhost").canonicalHostName).build()
        fingerprint = JarvisPairing.fingerprint(cert.certificate.encoded)
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.start()
        val jarvis = JarvisAccounts(secrets, HttpClients { OkHttpClient() }.secure, testDispatchers())
        val harness = AccountsHarness()
        accounts = Accounts(harness.store, harness.google, harness.scope, null, null, jarvis)
        feed = PrinterFeed { accounts.jarvisConnection() }
    }

    @After fun tearDown() = server.close()

    /** Pairs with the mock Jarvis; [firstShared] is what it says at pairing. */
    private suspend fun pair(firstShared: String = fixture("shared-with-printer")) {
        val address = "localhost:${server.port}"
        server.enqueue(json("""{"app":"jarvis-feed","version":1}"""))
        val found = accounts.probeJarvis(address) as JarvisAccounts.Probe.Found
        server.enqueue(json("""{"token":"T0KEN","deviceId":"d","pcName":"BEN-PC","version":1}"""))
        server.enqueue(json(firstShared))
        assertNull(accounts.addJarvis(found.base, found.fingerprint, "ABCDE-FGHJK", "Pixel 9"))
    }

    @Test fun `with no Jarvis paired there is no connection and the app says so`() = runBlocking {
        assertNull(accounts.jarvisConnection())
        assertEquals(PrinterAccess.NoJarvis, feed.access())
        assertEquals(0, server.requestCount)
    }

    @Test fun `Settings says what the paired Jarvis does about the printer`() = runBlocking {
        val line = com.edith.app.ui.settings.HomeSettingsViewModel.Companion::jarvisPrinterLine
        assertEquals("Jarvis: not paired", line(accounts.jarvisConnection()))
        pair()
        assertEquals("Jarvis: sharing the printer (FlashForge AD5X)", line(accounts.jarvisConnection()))
        server.enqueue(json(sharedWithoutPrinter))
        feed.access()   // looks again; the owner has switched the share off
        assertEquals("Jarvis: not sharing the printer", line(accounts.jarvisConnection()))
    }

    @Test fun `a paired Jarvis that shares the printer is ready, with what Jarvis said about it`() = runBlocking {
        pair()
        assertNotNull(accounts.jarvisConnection())
        server.enqueue(json(fixture("shared-with-printer")))
        val ready = feed.access() as PrinterAccess.Ready
        assertEquals("FlashForge AD5X", ready.shared.name)
        assertTrue(ready.shared.control)
        assertEquals(listOf("PLA", "PETG"), ready.shared.presets.map { it.name })
        // The api it hands out is signed with the paired token and pinned to the paired certificate: it can read the state.
        server.enqueue(json(fixture("state-ready-idle")))
        val r = ready.api.state(null, null, 0)
        assertEquals(41L, (r as FeedResult.Ok).value.rev)
        server.takeRequest()   // shared at pairing
        server.takeRequest()   // shared by access()
        assertEquals("Bearer T0KEN", server.takeRequest().headers["Authorization"])
    }

    @Test fun `a Jarvis that does not share the printer is NotShared, also an old one that does not know it`() = runBlocking {
        pair()
        server.enqueue(json(sharedWithoutPrinter))
        assertEquals(PrinterAccess.NotShared, feed.access())
    }

    @Test fun `the owner switching the share off is noticed on the next look`() = runBlocking {
        pair()
        server.enqueue(json(fixture("shared-with-printer")))
        assertTrue(feed.access() is PrinterAccess.Ready)
        server.enqueue(json(sharedWithoutPrinter))
        assertEquals(PrinterAccess.NotShared, feed.access())
    }

    @Test fun `a PC that is off is Away, never NotShared`() = runBlocking {
        pair()
        server.close()
        val away = feed.access() as PrinterAccess.Away
        assertEquals(PrinterWords.UNREACHABLE, away.why)
        assertEquals(false, away.removed)
    }

    @Test fun `a phone Jarvis has removed is told to pair again and not retried`() = runBlocking {
        pair()
        server.enqueue(json("""{"error":{"code":"UNAUTHORIZED","message":"no"}}""", 401))
        val away = feed.access() as PrinterAccess.Away
        assertEquals(PrinterWords.UNAUTHORIZED, away.why)
        assertTrue(away.removed)
    }

    @Test fun `a Jarvis with another certificate is reported as that, not as an unreachable PC`() = runBlocking {
        pair()
        val id = accounts.accounts.value.single().first.id
        val wrong = JarvisLink("https://localhost:${server.port}", "00".repeat(32), "T0KEN", "BEN-PC")
        secrets.put(AccountSecrets.jarvisLink(id), feedJson.encodeToString(JarvisLink.serializer(), wrong))
        accounts.connection(id)!!.start()   // reloads the saved link, as at app start
        val away = feed.access() as PrinterAccess.Away
        assertEquals(JarvisApi.MOVED, away.why)
    }
}

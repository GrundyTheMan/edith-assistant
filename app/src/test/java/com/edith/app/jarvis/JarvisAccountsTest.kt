package com.edith.app.jarvis

import com.edith.app.accounts.AccountSecrets
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.Accounts
import com.edith.app.accounts.Provider
import com.edith.app.accounts.tools.WhichAccount
import com.edith.app.core.net.HttpClients
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

/** C38: adding the work account through Jarvis, from the first look at the PC to a connected account. */
class JarvisAccountsTest {
    private val server = MockWebServer()
    private val secrets = InMemorySecretStore()
    private lateinit var fingerprint: String
    private lateinit var jarvis: JarvisAccounts
    private lateinit var accounts: Accounts

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
    private val shared = """{"pcName":"BEN-PC","mail":[{"id":"m","label":"Work","email":"me@corp.com"}],
        "calendars":[{"id":"c:1","label":"Work calendar"}],"tasks":[{"id":"t","label":"Outlook tasks"}]}"""

    @Before fun setUp() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName(InetAddress.getByName("localhost").canonicalHostName).build()
        fingerprint = JarvisPairing.fingerprint(cert.certificate.encoded)
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.start()
        jarvis = JarvisAccounts(secrets, HttpClients { OkHttpClient() }.secure, testDispatchers())
        val harness = AccountsHarness()
        accounts = Accounts(harness.store, harness.google, harness.scope, null, null, jarvis)
    }

    @After fun tearDown() = server.close()

    private val address get() = "localhost:${server.port}"

    private suspend fun pairIt(): String? {
        server.enqueue(json("""{"app":"jarvis-feed","version":1}"""))
        val found = accounts.probeJarvis(address) as JarvisAccounts.Probe.Found
        assertEquals(fingerprint, found.fingerprint)
        server.enqueue(json("""{"token":"T0KEN","deviceId":"d","pcName":"BEN-PC","version":1}"""))
        server.enqueue(json(shared))   // the first look at what Jarvis shares
        return accounts.addJarvis(found.base, found.fingerprint, "ABCDE-FGHJK", "Pixel 9")
    }

    @Test fun `pairing adds a connected account called Work, with mail, calendar and to-dos`() = runBlocking {
        assertNull(pairIt())
        val (account, state) = accounts.accounts.value.single()
        assertEquals(Provider.Jarvis, account.provider)
        assertEquals("BEN-PC", account.address)
        assertEquals("Work", account.label)
        assertEquals(AccountState.Connected, state)
        val entry = accounts.entries().single()
        assertTrue(entry.capabilities.mail && entry.capabilities.calendar && entry.capabilities.tasks)
        assertTrue(entry.calendar!!.readOnly)
        assertTrue(entry.tasks!!.readOnly)
        assertTrue("the token is kept, encrypted, under the account", secrets.get(AccountSecrets.jarvisLink(account.id))!!.contains("T0KEN"))
    }

    @Test fun `the prompt names the work account and what it can do`() = runBlocking {
        pairIt()
        assertEquals("Accounts: Work — BEN-PC (Jarvis: calendar, email and to-dos).", accounts.connectedNote(emptyList()))
    }

    @Test fun `a refused code adds nothing and says why`() = runBlocking {
        server.enqueue(json("""{"app":"jarvis-feed","version":1}"""))
        val found = accounts.probeJarvis(address) as JarvisAccounts.Probe.Found
        server.enqueue(json("""{"error":{"code":"PAIRING_REFUSED","message":"no"}}""", 403))
        val problem = accounts.addJarvis(found.base, found.fingerprint, "ABCDE-FGHJK", "Pixel 9")
        assertEquals(JarvisApi.PAIRING_REFUSED, problem)
        assertTrue(accounts.accounts.value.isEmpty())
    }

    @Test fun `a code of the wrong length is caught before anything is sent`() = runBlocking {
        val problem = accounts.addJarvis("https://localhost:${server.port}/", fingerprint, "ABC", "Pixel")
        assertNotNull(problem)
        assertEquals(0, server.requestCount)
    }

    @Test fun `an address that isn't there says Jarvis can't be reached`() = runBlocking {
        server.close()
        val r = accounts.probeJarvis(address) as JarvisAccounts.Probe.Failed
        assertEquals(JarvisApi.NOT_REACHABLE, r.message)
    }

    @Test fun `a new event is never offered to the work calendar`() = runBlocking {
        pairIt()
        val choice = WhichAccount.forCalendar(accounts, null, null)
        assertTrue("only a read-only calendar exists: $choice", choice is WhichAccount.Choice.Stop)
    }

    @Test fun `pairing the same PC again keeps one account`() = runBlocking {
        pairIt()
        assertNull(pairIt())
        assertEquals(1, accounts.accounts.value.size)
    }

    @Test fun `pairing again after Jarvis removed the phone uses the new token`() = runBlocking {
        pairIt()
        server.enqueue(json("""{"error":{"code":"UNAUTHORIZED","message":"x"}}""", 401))
        val entry = accounts.entries().single()
        entry.mail!!.search(com.edith.app.accounts.sources.MailQuery(), 5)
        assertTrue(accounts.accounts.value.single().second is AccountState.NeedsSignIn)
        // Pair again: a different token, and a fresh look at what Jarvis shares.
        server.enqueue(json("""{"app":"jarvis-feed","version":1}"""))
        val found = accounts.probeJarvis(address) as JarvisAccounts.Probe.Found
        server.enqueue(json("""{"token":"N3W-T0KEN","deviceId":"d2","pcName":"BEN-PC","version":1}"""))
        server.enqueue(json(shared))
        assertNull(accounts.addJarvis(found.base, found.fingerprint, "ABCDE-FGHJK", "Pixel 9"))
        assertEquals(AccountState.Connected, accounts.accounts.value.single().second)
        val requests = (1..server.requestCount).map { server.takeRequest() }
        assertEquals("the look after pairing carries the new token", "Bearer N3W-T0KEN", requests.last().headers["Authorization"])
    }

    @Test fun `removing the account forgets the pairing on the phone`() = runBlocking {
        pairIt()
        val id = accounts.accounts.value.single().first.id
        accounts.disconnect(id)
        assertNull(secrets.get(AccountSecrets.jarvisLink(id)))
        assertTrue(accounts.accounts.value.isEmpty())
    }
}

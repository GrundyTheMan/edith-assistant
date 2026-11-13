package com.edith.app.microsoft

import android.app.Activity
import com.edith.app.accounts.Account
import com.edith.app.accounts.AccountSecrets
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.Provider
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.LogRingBuffer
import com.edith.app.google.browser.BrowserOAuth
import com.edith.app.testing.AccountsHarness
import com.edith.app.testing.FakeClock
import com.edith.app.testing.InMemorySecretStore
import com.edith.app.testing.InMemoryAccountStore
import com.edith.app.testing.testDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.net.URLDecoder
import java.time.Instant
import java.util.Base64

/** C23 §1, §4: Microsoft's sign-in and an account's access, against a stand-in for Microsoft (MockWebServer). */
class MicrosoftAuthTest {
    private val server = MockWebServer()
    private val dispatchers = testDispatchers().copy(io = Dispatchers.IO)
    private val secrets = InMemorySecretStore()
    private val clock = FakeClock(Instant.parse("2026-11-12T18:00:00Z"))
    private val account = Account("m1", Provider.Microsoft, "owner@outlook.com", "outlook")
    private val key = AccountSecrets.refreshToken("m1")

    @Before fun setUp() {
        server.start()
        LogRingBuffer.clear()
    }

    @After fun tearDown() = server.close()

    private fun oauth() = BrowserOAuth(lazy { OkHttpClient() }, dispatchers, MicrosoftOAuth.provider("ms-client-id", loginBase = server.url("/").toString()))

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    private fun form(body: String): Map<String, String> = body.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    private fun auth(o: BrowserOAuth = oauth(), signedInAgain: MutableList<String> = mutableListOf()) =
        MicrosoftAccountAuth({ account }, o, secrets, clock) { signedInAgain += "again" }

    // --- The provider -------------------------------------------------------------------------------------------

    @Test fun `the sign-in address - common tenant, Graph scopes, the account picker or a hint`() {
        val o = oauth()
        val add = o.authUrl("http://localhost:5555", "the-challenge", "the-state", loginHint = null).toHttpUrl()
        assertEquals("/common/oauth2/v2.0/authorize", add.encodedPath)
        assertEquals("ms-client-id", add.queryParameter("client_id"))
        assertEquals("http://localhost:5555", add.queryParameter("redirect_uri"))
        assertEquals("code", add.queryParameter("response_type"))
        assertEquals("S256", add.queryParameter("code_challenge_method"))
        assertEquals("the-challenge", add.queryParameter("code_challenge"))
        assertEquals("the-state", add.queryParameter("state"))
        assertEquals("select_account", add.queryParameter("prompt"))
        assertNull(add.queryParameter("login_hint"))
        assertNull("Google's parameter isn't sent", add.queryParameter("access_type"))
        assertEquals(
            setOf("offline_access", "openid", "email", "User.Read", "Mail.ReadWrite", "Mail.Send", "Calendars.ReadWrite"),
            add.queryParameter("scope")!!.split(' ').toSet(),
        )
        val again = o.authUrl("http://localhost:5555", "c", "s", loginHint = "owner@outlook.com").toHttpUrl()
        assertEquals("owner@outlook.com", again.queryParameter("login_hint"))
        assertNull(again.queryParameter("prompt"))
        assertTrue(o.provider.localhostRedirect)
        assertEquals("consumers would be personal only (D18)", "https://login.microsoftonline.com/common/oauth2/v2.0/token", MicrosoftOAuth.provider("x").tokenUrl)
    }

    @Test fun `the exchange and refresh send no secret, and the address comes from the id_token`() = runBlocking {
        server.enqueue(json(tokens(access = "eyJ.first", refresh = "M.R3_first", claims = """"preferred_username":"owner@outlook.com"""")))
        val t = oauth().exchange("M.C5_code", "the-verifier", "http://localhost:5555") as BrowserOAuth.TokenResult.Tokens
        assertEquals("owner@outlook.com", t.email)
        assertEquals("M.R3_first", t.refreshToken)
        val request = server.takeRequest()
        assertEquals("/common/oauth2/v2.0/token", request.url.encodedPath)
        val exchange = form(request.body!!.utf8())
        assertEquals("authorization_code", exchange["grant_type"])
        assertEquals("ms-client-id", exchange["client_id"])
        assertEquals("http://localhost:5555", exchange["redirect_uri"])
        assertEquals(MicrosoftOAuth.SCOPE, exchange["scope"])
        assertFalse("a public client has no secret", exchange.containsKey("client_secret"))

        server.enqueue(json(tokens(access = "eyJ.second", refresh = "M.R3_second", claims = """"email":"owner@outlook.com","preferred_username":"other"""")))
        val r = oauth().refresh("M.R3_first") as BrowserOAuth.TokenResult.Tokens
        assertEquals("email first", "owner@outlook.com", r.email)
        val refresh = form(server.takeRequest().body!!.utf8())
        assertEquals("refresh_token", refresh["grant_type"])
        assertEquals("M.R3_first", refresh["refresh_token"])
        assertFalse(refresh.containsKey("client_secret"))
    }

    @Test fun `token errors - expired, admin approval, a bad client, no network`() = runBlocking {
        server.enqueue(json("""{"error":"invalid_grant","error_description":"AADSTS70000: The provided grant has expired.","error_codes":[70000]}""", 400))
        assertEquals(BrowserOAuth.TokenResult.InvalidGrant, oauth().refresh("x"))
        server.enqueue(json("""{"error":"invalid_grant","error_description":"AADSTS65001: The user or administrator has not consented to use the application.","error_codes":[65001]}""", 400))
        assertEquals(BrowserOAuth.TokenResult.ConsentRequired, oauth().refresh("x"))
        server.enqueue(json("""{"error":"unauthorized_client","error_description":"AADSTS700016: Application not found."}""", 400))
        assertEquals(BrowserOAuth.TokenResult.InvalidClient, oauth().refresh("x"))
        server.close()
        assertEquals(BrowserOAuth.TokenResult.NoNetwork, oauth().refresh("x"))
    }

    @Test fun `a redirect saying an admin must approve is told apart from the owner declining`() {
        val o = oauth()
        assertEquals(
            BrowserOAuth.Redirect.AdminApproval,
            o.parseRedirect("http://localhost:5555/?error=access_denied&error_description=AADSTS65001%3a+The+user+or+administrator+has+not+consented&state=s1", "s1"),
        )
        assertEquals(
            BrowserOAuth.Redirect.AdminApproval,
            o.parseRedirect("http://localhost:5555/?error=consent_required&error_description=AADSTS90094%3a+admin+approval&state=s1", "s1"),
        )
        assertEquals(
            BrowserOAuth.Redirect.Denied,
            o.parseRedirect("http://localhost:5555/?error=access_denied&error_description=AADSTS65004%3a+User+declined+to+consent&state=s1", "s1"),
        )
        assertEquals(BrowserOAuth.Redirect.Code("M.C5_code"), o.parseRedirect("localhost:5555/?code=M.C5_code&state=s1", "s1"))
    }

    // --- One account's access -----------------------------------------------------------------------------------

    @Test fun `every refresh stores the rotated refresh token before the access token is used`() = runBlocking {
        secrets.put(key, "M.R3_old")
        server.enqueue(json(tokens(access = "eyJ.one", refresh = "M.R3_new")))
        val a = auth()
        var storedWhenUsed: String? = null
        val r = a.authorized { token ->
            storedWhenUsed = secrets.get(key)
            GraphCall.Ok(token)
        }
        assertEquals(Outcome.Success("eyJ.one"), r)
        assertEquals("trap 1: saved first", "M.R3_new", storedWhenUsed)
        assertEquals(AccountState.Connected, a.state.value)
        assertEquals("M.R3_old", form(server.takeRequest().body!!.utf8())["refresh_token"])

        // Cached until it nearly expires; then the newest refresh token is the one sent.
        assertEquals(Outcome.Success("eyJ.one"), a.accessToken())
        clock.instant = clock.instant.plusSeconds(3600)
        server.enqueue(json(tokens(access = "eyJ.two", refresh = "M.R3_newer")))
        assertEquals(Outcome.Success("eyJ.two"), a.accessToken())
        assertEquals("M.R3_new", form(server.takeRequest().body!!.utf8())["refresh_token"])
        assertEquals("M.R3_newer", secrets.get(key))

        val log = LogRingBuffer.tail(LogRingBuffer.CAPACITY).joinToString("\n")
        listOf("M.R3_old", "M.R3_new", "eyJ.one", "eyJ.two").forEach { assertFalse("'$it' in log", log.contains(it)) }
    }

    @Test fun `invalid_grant needs signing in again, keeping the account, and Sign in again opens the browser`() = runBlocking {
        secrets.put(key, "M.R3_expired")
        server.enqueue(json("""{"error":"invalid_grant","error_description":"AADSTS700082: The refresh token has expired due to inactivity."}""", 400))
        val again = mutableListOf<String>()
        val a = auth(signedInAgain = again)
        a.check()
        assertEquals(AccountState.NeedsSignIn(MicrosoftAccountAuth.SIGN_IN_AGAIN, viaBrowser = true), a.state.value)
        assertNull("the dead token is dropped", secrets.get(key))
        a.grant(Activity())
        assertEquals(listOf("again"), again)
    }

    @Test fun `no network keeps the token and says Try again, which checks quietly first`() = runBlocking {
        secrets.put(key, "M.R3_good")
        val down = MockWebServer().apply { start() }
        val url = down.url("/").toString()
        down.close()
        val o = BrowserOAuth(lazy { OkHttpClient() }, dispatchers, MicrosoftOAuth.provider("ms-client-id", loginBase = url))
        val again = mutableListOf<String>()
        val a = auth(o, again)
        a.check()
        assertEquals(AccountState.Error(MicrosoftAccountAuth.NO_NETWORK), a.state.value)
        assertEquals("M.R3_good", secrets.get(key))

        val back = MicrosoftAccountAuth({ account }, oauth(), secrets, clock) { again += "again" }
        server.enqueue(json(tokens(access = "eyJ.back", refresh = "M.R3_next")))
        back.grant(Activity())
        assertEquals(AccountState.Connected, back.state.value)
        assertTrue("no browser when a quiet check works", again.isEmpty())
    }

    @Test fun `a 401 refreshes once and tries again, a second 401 or a 403 needs signing in`() = runBlocking {
        secrets.put(key, "M.R3")
        repeat(3) { server.enqueue(json(tokens(access = "eyJ.$it", refresh = "M.R3_$it"))) }
        val a = auth()
        val seen = mutableListOf<String>()
        val twice = a.authorized<String> { token -> seen += token; if (seen.size == 1) GraphCall.Unauthorized else GraphCall.Ok("fine") }
        assertEquals(Outcome.Success("fine"), twice)
        assertEquals(listOf("eyJ.0", "eyJ.1"), seen)

        val refused = a.authorized<String> { GraphCall.Unauthorized }
        assertTrue(refused is Outcome.Failure)
        assertTrue(a.state.value is AccountState.NeedsSignIn)

        val b = auth()
        server.enqueue(json(tokens(access = "eyJ.x", refresh = "M.R3_x")))
        assertTrue(b.authorized<String> { GraphCall.Forbidden } is Outcome.Failure)
        assertTrue(b.state.value is AccountState.NeedsSignIn)
    }

    // --- Add account → Microsoft, through the loopback ------------------------------------------------------------

    @Test fun `Add account - Microsoft's page, the code back on localhost, the account added and Connected`() = runBlocking {
        val h = AccountsHarness(baseUrl = server.url("/").toString(), store = InMemoryAccountStore())
        server.enqueue(json(tokens(access = "eyJ.first", refresh = "M.R3_first", claims = """"preferred_username":"Owner@Outlook.com"""")))
        val adding = async(Dispatchers.IO) { h.accounts.addMicrosoft(Activity()) }
        withTimeout(5_000) { while (synchronized(h.opened) { h.opened.isEmpty() }) delay(10) }
        val page = synchronized(h.opened) { h.opened.single() }.toHttpUrl()
        assertEquals("/ms/common/oauth2/v2.0/authorize", page.encodedPath)
        val redirect = page.queryParameter("redirect_uri")!!.toHttpUrl()
        assertEquals("localhost", redirect.host)
        assertTrue(h.accounts.browserWaiting.value)
        assertEquals("microsoft", h.accounts.browserWaitingFor.value)
        Socket(InetAddress.getByName("127.0.0.1"), redirect.port).use { s ->
            s.getOutputStream().write("GET /?code=M.C5_code&state=${page.queryParameter("state")} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            s.getInputStream().readBytes()
        }
        adding.await()

        val (added, state) = h.accounts.accounts.value.single()
        assertEquals(Provider.Microsoft, added.provider)
        assertEquals("Owner@Outlook.com", added.address)
        assertEquals("the sign-in's tokens are used: no refresh yet", AccountState.Connected, state)
        assertEquals("M.R3_first", h.secrets.get(AccountSecrets.refreshToken(added.id)))
        assertNull(h.accounts.message.value)
        assertFalse(h.accounts.browserWaiting.value)
    }

    @Test fun `without an address in the id_token, Graph's me says who it is`() = runBlocking {
        val h = AccountsHarness(baseUrl = server.url("/").toString())
        server.enqueue(json("""{"mail":null,"userPrincipalName":"sam@contoso.com"}"""))
        val r = h.microsoft.result(
            com.edith.app.google.browser.BrowserSignIn.Result.Tokens(
                BrowserOAuth.TokenResult.Tokens("eyJ.first", 3600, "M.R3_first", null, null),
            ),
        )
        assertEquals("sam@contoso.com", (r as MicrosoftAccounts.Result.SignedIn).address)
        val me = server.takeRequest()
        assertEquals("/graph/v1.0/me", me.url.encodedPath)
        assertEquals("Bearer eyJ.first", me.headers["Authorization"])
    }

    @Test fun `a work account whose admin must approve says so`() = runBlocking {
        val h = AccountsHarness(baseUrl = server.url("/").toString())
        val adding = async(Dispatchers.IO) { h.accounts.addMicrosoft(Activity()) }
        withTimeout(5_000) { while (synchronized(h.opened) { h.opened.isEmpty() }) delay(10) }
        val page = synchronized(h.opened) { h.opened.single() }.toHttpUrl()
        h.accounts.pasteBrowserAddress(
            "http://localhost:${page.queryParameter("redirect_uri")!!.toHttpUrl().port}/?error=access_denied&error_description=AADSTS90094%3a+Admin+approval+required&state=${page.queryParameter("state")}",
        )
        adding.await()
        assertEquals(MicrosoftAccounts.ADMIN_APPROVAL, h.accounts.message.value)
        assertTrue(h.accounts.accounts.value.isEmpty())
        assertEquals("nothing was exchanged", 0, server.requestCount)
    }

    @Test fun `without a client ID Microsoft isn't set up`() = runBlocking {
        val h = AccountsHarness(microsoftClientId = "")
        assertFalse(h.microsoft.isConfigured)
        h.accounts.addMicrosoft(Activity())
        assertEquals(MicrosoftAccounts.NOT_SET_UP, h.accounts.message.value)
        assertTrue(h.opened.isEmpty())
    }

    @Test fun `disconnect deletes the token on the phone and calls nobody`() = runBlocking {
        val h = AccountsHarness(baseUrl = server.url("/").toString(), store = InMemoryAccountStore(listOf(account)))
        h.secrets.put(key, "M.R3")
        server.enqueue(json(tokens(access = "eyJ.a", refresh = "M.R3_b")))
        h.accounts.load()
        withTimeout(5_000) { while (h.accounts.accounts.value.single().second != AccountState.Connected) delay(10) }
        val before = server.requestCount
        h.accounts.disconnect("m1")
        assertTrue(h.accounts.accounts.value.isEmpty())
        assertNull(h.secrets.get(key))
        assertEquals("Microsoft has no revoke endpoint", before, server.requestCount)
    }

    companion object {
        fun idToken(claims: String): String {
            val enc = Base64.getUrlEncoder().withoutPadding()
            return enc.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray()) + "." +
                enc.encodeToString("""{"iss":"https://login.microsoftonline.com/9188040d-6c67-4c5b-b112-36a304b66dad/v2.0","tid":"9188040d-6c67-4c5b-b112-36a304b66dad",$claims}""".toByteArray()) +
                ".signature-not-checked"
        }

        fun tokens(access: String, refresh: String, claims: String = """"preferred_username":"owner@outlook.com""""): String =
            """{"token_type":"Bearer","scope":"Mail.ReadWrite Mail.Send Calendars.ReadWrite User.Read openid email","expires_in":3600,"ext_expires_in":3600,"access_token":"$access","refresh_token":"$refresh","id_token":"${idToken(claims)}"}"""
    }
}

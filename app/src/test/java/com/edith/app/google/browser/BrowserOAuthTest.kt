package com.edith.app.google.browser

import com.edith.app.google.GoogleScope
import com.edith.app.testing.testDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.util.Base64

/** C17b: Google's OAuth endpoints for the Desktop client, against MockWebServer (values made up). */
class BrowserOAuthTest {
    private val server = MockWebServer()
    private val dispatchers = testDispatchers().copy(io = Dispatchers.IO)
    private lateinit var tokenUrl: String

    @Before fun setUp() {
        server.start()
        tokenUrl = server.url("/token").toString()
    }

    @After fun tearDown() = server.close()

    private fun oauth(secret: String = "") = BrowserOAuth(
        lazy { OkHttpClient() }, dispatchers, clientId = "desktop.apps.googleusercontent.com", clientSecret = secret,
        tokenUrl = tokenUrl, revokeUrl = server.url("/revoke").toString(),
    )

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    private fun form(body: String): Map<String, String> = body.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    @Test fun `PKCE matches RFC 7636 Appendix B and the verifier is well formed`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", BrowserOAuth.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        repeat(20) {
            val p = oauth().pkce()
            assertTrue(p.verifier.length in 43..128)
            assertTrue(p.verifier, p.verifier.all { c -> c.isLetterOrDigit() || c in "-._~" })
            assertEquals(BrowserOAuth.challengeFor(p.verifier), p.challenge)
        }
    }

    @Test fun `the sign-in address carries every parameter`() {
        val url = oauth().authUrl("http://127.0.0.1:5555", "the-challenge", "the-state", GoogleScope.entries.toSet(), "owner@gmail.com").toHttpUrl()
        assertEquals("accounts.google.com", url.host)
        assertEquals("/o/oauth2/v2/auth", url.encodedPath)
        assertEquals("desktop.apps.googleusercontent.com", url.queryParameter("client_id"))
        assertEquals("http://127.0.0.1:5555", url.queryParameter("redirect_uri"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("the-challenge", url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("the-state", url.queryParameter("state"))
        assertEquals("offline", url.queryParameter("access_type"))
        assertEquals("C35: a sign-in for To-dos keeps the other grants", "true", url.queryParameter("include_granted_scopes"))
        assertEquals("consent", url.queryParameter("prompt"))
        assertEquals("owner@gmail.com", url.queryParameter("login_hint"))
        val scopes = url.queryParameter("scope")!!.split(' ').toSet()
        assertEquals(setOf("openid", "email") + GoogleScope.entries.map { it.uri }, scopes)
        val chooser = oauth().authUrl("http://127.0.0.1:1", "c", "s", GoogleScope.entries.toSet()).toHttpUrl()
        assertNull(chooser.queryParameter("login_hint"))
        assertEquals("a new account (C22): Google's account chooser", "select_account consent", chooser.queryParameter("prompt"))
    }

    @Test fun `the exchange sends the form and reads tokens and the email`() = runBlocking {
        server.enqueue(json(tokens(refresh = "1//refresh", email = "owner@gmail.com")))
        val r = oauth().exchange("4/code", "the-verifier", "http://127.0.0.1:5555") as BrowserOAuth.TokenResult.Tokens
        assertEquals("ya29.access", r.accessToken)
        assertEquals(3599L, r.expiresInSeconds)
        assertEquals("1//refresh", r.refreshToken)
        assertEquals("owner@gmail.com", r.email)
        assertEquals(GoogleScope.entries.map { it.uri }.toSet() + "openid", r.scopes)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals(
            mapOf(
                "grant_type" to "authorization_code", "code" to "4/code", "code_verifier" to "the-verifier",
                "redirect_uri" to "http://127.0.0.1:5555", "client_id" to "desktop.apps.googleusercontent.com",
            ),
            form(sent.body!!.utf8()),
        )
    }

    @Test fun `the client secret is sent only when configured`() = runBlocking {
        server.enqueue(json(tokens()))
        oauth(secret = "GOCSPX-made-up").exchange("c", "v", "http://127.0.0.1:1")
        assertEquals("GOCSPX-made-up", form(server.takeRequest().body!!.utf8())["client_secret"])
    }

    @Test fun `refresh sends the refresh token and maps Google's errors`() = runBlocking {
        server.enqueue(json("""{"access_token":"ya29.new","expires_in":3599,"scope":"https://www.googleapis.com/auth/calendar.events","token_type":"Bearer"}"""))
        val r = oauth().refresh("1//refresh") as BrowserOAuth.TokenResult.Tokens
        assertEquals("ya29.new", r.accessToken)
        assertNull(r.refreshToken)
        assertEquals(setOf(GoogleScope.CalendarEvents.uri), r.scopes)
        val sent = form(server.takeRequest().body!!.utf8())
        assertEquals("refresh_token", sent["grant_type"])
        assertEquals("1//refresh", sent["refresh_token"])

        server.enqueue(json("""{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""", 400))
        assertEquals(BrowserOAuth.TokenResult.InvalidGrant, oauth().refresh("x"))
        server.enqueue(json("""{"error":"invalid_client","error_description":"The OAuth client was not found."}""", 401))
        assertEquals(BrowserOAuth.TokenResult.InvalidClient, oauth().refresh("x"))
        server.enqueue(json("""{"error":"invalid_request","error_description":"client_secret is missing."}""", 400))
        assertEquals(BrowserOAuth.TokenResult.InvalidClient, oauth().refresh("x"))
        server.enqueue(json("""{"error":"server_error"}""", 500))
        assertEquals(BrowserOAuth.TokenResult.Failed("server_error"), oauth().refresh("x"))
        server.close()
        assertEquals(BrowserOAuth.TokenResult.NoNetwork, oauth().refresh("x"))
    }

    @Test fun `revoke posts the token`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).build())
        assertTrue(oauth().revoke("1//refresh"))
        val sent = server.takeRequest()
        assertEquals("/revoke", sent.url.encodedPath)
        assertEquals(mapOf("token" to "1//refresh"), form(sent.body!!.utf8()))
    }

    @Test fun `redirect addresses are read whether pasted or not`() {
        val o = oauth()
        assertEquals(BrowserOAuth.Redirect.Code("4/0Ab"), o.parseRedirect("http://127.0.0.1:5555/?state=s1&code=4/0Ab&scope=email", "s1"))
        assertEquals("spaces and no scheme", BrowserOAuth.Redirect.Code("4/0Ab"), o.parseRedirect("  127.0.0.1:5555/?state=s1&code=4%2F0Ab \n", "s1"))
        assertEquals(BrowserOAuth.Redirect.Denied, o.parseRedirect("http://127.0.0.1:5555/?error=access_denied&state=s1", "s1"))
        assertEquals(BrowserOAuth.Redirect.Error("invalid_scope"), o.parseRedirect("http://127.0.0.1:5555/?error=invalid_scope&state=s1", "s1"))
        assertEquals(BrowserOAuth.Redirect.WrongState, o.parseRedirect("http://127.0.0.1:5555/?state=old&code=4/0Ab", "s1"))
        assertEquals(BrowserOAuth.Redirect.WrongState, o.parseRedirect("http://127.0.0.1:5555/?code=4/0Ab", "s1"))
        assertEquals(BrowserOAuth.Redirect.WrongState, o.parseRedirect("not an address", "s1"))
        assertEquals(BrowserOAuth.Redirect.Error("no_code"), o.parseRedirect("http://127.0.0.1:5555/?state=s1", "s1"))
    }

    @Test fun `the email comes from the id_token payload`() {
        assertEquals("owner@gmail.com", BrowserOAuth.emailFromIdToken(idToken("owner@gmail.com")))
        assertNull(BrowserOAuth.emailFromIdToken("garbage"))
    }

    companion object {
        fun idToken(email: String): String {
            val enc = Base64.getUrlEncoder().withoutPadding()
            val header = enc.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
            val payload = enc.encodeToString("""{"iss":"https://accounts.google.com","sub":"110169484474386276334","email":"$email","email_verified":true}""".toByteArray())
            return "$header.$payload.signature-not-checked"
        }

        fun tokens(access: String = "ya29.access", refresh: String? = "1//refresh", email: String = "owner@gmail.com", scopes: Collection<String> = GoogleScope.entries.map { it.uri } + "openid"): String =
            """{"access_token":"$access","expires_in":3599,${refresh?.let { "\"refresh_token\":\"$it\"," } ?: ""}"scope":"${scopes.joinToString(" ")}","token_type":"Bearer","id_token":"${idToken(email)}"}"""
    }
}

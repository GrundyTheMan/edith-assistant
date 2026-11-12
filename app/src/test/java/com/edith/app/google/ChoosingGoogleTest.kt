package com.edith.app.google

import android.app.Activity
import android.content.Intent
import com.edith.app.google.browser.BrowserGoogle
import com.edith.app.google.browser.BrowserOAuth
import com.edith.app.google.browser.BrowserOAuthTest
import com.edith.app.testing.FakeClock
import com.edith.app.testing.FakeSettingsRepository
import com.edith.app.testing.InMemorySecretStore
import com.edith.app.testing.testDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/** C17b: which way Edith reaches Google (Play services or the browser), and GoogleAuth on the browser path. */
class ChoosingGoogleTest {
    private class FakePlaySignIn : GoogleSignIn {
        var signIns = 0
        override suspend fun signIn(activity: Activity, webClientId: String): GoogleSignIn.Result {
            signIns++
            return GoogleSignIn.Result.SignedIn("owner@gmail.com", "Owner")
        }
        override suspend fun signOut() = Unit
    }

    private class FakePlayAuthorizer : GoogleAuthorizer {
        var asked = 0
        val revoked = mutableListOf<String>()
        override suspend fun authorize(scopes: Set<GoogleScope>): GoogleAuthorizer.Result { asked++; return GoogleAuthorizer.Result.Granted("ya29.play") }
        override fun fromConsent(data: Intent?) = GoogleAuthorizer.Result.Granted("ya29.play")
        override suspend fun clearToken(token: String) = Unit
        override suspend fun revoke(email: String) { revoked += email }
    }

    private val server = MockWebServer()
    private val secrets = InMemorySecretStore()
    private val settings = FakeSettingsRepository()
    private val play = FakePlaySignIn()
    private val playAuth = FakePlayAuthorizer()
    private val opened = mutableListOf<String>()
    private var playAvailable = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun browser(clientId: String = "desktop.apps.googleusercontent.com") = BrowserGoogle(
        BrowserOAuth(lazy { OkHttpClient() }, testDispatchers().copy(io = Dispatchers.IO), clientId, tokenUrl = server.url("/token").toString(), revokeUrl = server.url("/revoke").toString()),
        secrets, FakeClock(), testDispatchers().copy(io = Dispatchers.IO), "com.edith.app.debug://signed-in",
        openBrowser = { _, url -> synchronized(opened) { opened += url } },
    )

    private fun choosing(web: Boolean = true, b: BrowserGoogle = browser()) =
        ChoosingGoogle(play, playAuth, b, settings, playConfigured = web, playServicesAvailable = { playAvailable })

    private fun googleAuth(c: ChoosingGoogle, web: String = "web", desktop: String = "desktop") =
        GoogleAuth(c, c, settings, web, scope, desktopClientId = desktop, browser = c)

    private fun json(body: String) = MockResponse.Builder().code(200).body(body).addHeader("Content-Type", "application/json").build()

    /** Plays the owner in the browser: waits for Google's page, then pastes the address it would end on. */
    private suspend fun finishInBrowser(c: ChoosingGoogle) {
        withTimeout(5_000) { while (!c.waiting.value || synchronized(opened) { opened.isEmpty() }) delay(10) }
        val page = synchronized(opened) { opened.last() }.toHttpUrl()
        c.paste("${page.queryParameter("redirect_uri")}/?state=${page.queryParameter("state")}&code=4/code")
    }

    @Test fun `the choice rule`() {
        fun choose(browserSetUp: Boolean = true, web: Boolean = true, play: Boolean = true, token: Boolean = false, last: Boolean = false) =
            ChoosingGoogle.chooseBrowser(browserSetUp, web, play, token, last)
        assertFalse("Play services by default", choose())
        assertTrue("Play services unavailable", choose(play = false))
        assertTrue("a browser refresh token is stored", choose(token = true))
        assertTrue("the last sign-in was through the browser", choose(last = true))
        assertTrue("no Web client ID: the browser is the only way", choose(web = false))
        assertFalse("never the browser when it isn't set up", choose(browserSetUp = false, play = false, token = true, last = true))
    }

    @Test fun `with Play services Sign in uses Play`() = runBlocking {
        val c = choosing()
        val g = googleAuth(c)
        g.signIn(Activity())
        assertEquals(1, play.signIns)
        assertEquals(GoogleState.Connected("owner@gmail.com"), g.state.value)
        assertEquals(ChoosingGoogle.MODE_PLAY, settings.current().googleSignInMode)
        assertTrue(opened.isEmpty())
    }

    @Test fun `without Play services Sign in opens the browser`() = runBlocking {
        playAvailable = false
        server.enqueue(json(BrowserOAuthTest.tokens(access = "ya29.browser")))
        val c = choosing()
        val g = googleAuth(c)
        val signIn = async(Dispatchers.IO) { g.signIn(Activity()) }
        finishInBrowser(c)
        signIn.await()
        assertEquals(0, play.signIns)
        assertEquals(0, playAuth.asked)
        assertEquals(GoogleState.Connected("owner@gmail.com"), g.state.value)
        assertEquals(ChoosingGoogle.MODE_BROWSER, settings.current().googleSignInMode)
        assertEquals("1//refresh", secrets.get(BrowserGoogle.REFRESH_TOKEN_KEY))
    }

    @Test fun `Use a browser instead forces the browser, then disconnect revokes and deletes`() = runBlocking {
        server.enqueue(json(BrowserOAuthTest.tokens(access = "ya29.browser")))
        val c = choosing()
        val g = googleAuth(c)
        val signIn = async(Dispatchers.IO) { g.signInWithBrowser(Activity()) }
        finishInBrowser(c)
        signIn.await()
        assertEquals(0, play.signIns)
        assertEquals(GoogleState.Connected("owner@gmail.com"), g.state.value)
        assertTrue("browser mode from now on", c.usesBrowser())
        assertEquals("tools get the browser's token", com.edith.app.core.error.Outcome.Success("ya29.browser"), g.accessToken(setOf(GoogleScope.CalendarEvents)))
        assertEquals(0, playAuth.asked)
        server.takeRequest()   // the exchange

        server.enqueue(MockResponse.Builder().code(200).build())
        g.disconnect()
        assertEquals("/revoke", server.takeRequest().url.encodedPath)
        assertNull(secrets.get(BrowserGoogle.REFRESH_TOKEN_KEY))
        assertNull(settings.current().googleSignInMode)
        assertNull(settings.current().googleAccountEmail)
        assertTrue("Play's access isn't touched", playAuth.revoked.isEmpty())
        assertFalse("back to Play services", c.usesBrowser())
        assertEquals(GoogleState.SignedOut, g.state.value)
    }

    @Test fun `an expired browser grant shows Grant access, which signs in through the browser again`() = runBlocking {
        secrets.put(BrowserGoogle.REFRESH_TOKEN_KEY, "1//expired")
        settings.update { it.copy(googleAccountEmail = "owner@gmail.com", googleSignInMode = ChoosingGoogle.MODE_BROWSER) }
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":"invalid_grant"}""").build())
        val c = choosing()
        val g = googleAuth(c)
        g.start()
        assertEquals(GoogleState.NeedsConsent("owner@gmail.com", viaBrowser = true), g.state.value)

        server.enqueue(json(BrowserOAuthTest.tokens(access = "ya29.again")))
        val grant = async(Dispatchers.IO) { g.grant(Activity()) { error("no consent screen on the browser path") } }
        finishInBrowser(c)
        grant.await()
        assertEquals(GoogleState.Connected("owner@gmail.com"), g.state.value)
        assertEquals(0, play.signIns)
    }

    @Test fun `no network at startup keeps the grant and offers Try again`() = runBlocking {
        secrets.put(BrowserGoogle.REFRESH_TOKEN_KEY, "1//kept")
        settings.update { it.copy(googleAccountEmail = "owner@gmail.com", googleSignInMode = ChoosingGoogle.MODE_BROWSER) }
        server.close()
        val g = googleAuth(choosing())
        g.start()
        assertEquals(GoogleState.Error("owner@gmail.com", BrowserGoogle.NO_NETWORK), g.state.value)
        assertEquals("1//kept", secrets.get(BrowserGoogle.REFRESH_TOKEN_KEY))
    }

    @Test fun `Google is not set up only when both client IDs are missing`() {
        assertEquals(GoogleState.NotSetUp, googleAuth(choosing(web = false, b = browser(clientId = "")), web = "", desktop = "").state.value)
        assertEquals("Desktop only: the browser", GoogleState.SignedOut, googleAuth(choosing(web = false), web = "").state.value)
        assertEquals("Web only: Play services", GoogleState.SignedOut, googleAuth(choosing(b = browser(clientId = "")), desktop = "").state.value)
        assertFalse(googleAuth(choosing(b = browser(clientId = "")), desktop = "").browserAvailable)
    }
}

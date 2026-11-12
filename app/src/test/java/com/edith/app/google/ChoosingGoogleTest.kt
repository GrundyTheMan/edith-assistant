package com.edith.app.google

import android.app.Activity
import com.edith.app.accounts.Account
import com.edith.app.accounts.AccountSecrets
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.Provider
import com.edith.app.core.error.Outcome
import com.edith.app.google.browser.BrowserGoogle
import com.edith.app.google.browser.BrowserOAuthTest
import com.edith.app.testing.AccountsHarness
import com.edith.app.testing.InMemoryAccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * C17b: which way each account reaches Google (Play services or the browser), and the browser path through
 * [com.edith.app.accounts.Accounts] (C22: per account, with Google's account chooser for a new one).
 */
class ChoosingGoogleTest {
    private val server = MockWebServer()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun harness(store: InMemoryAccountStore = InMemoryAccountStore(), web: String = "web", desktop: String = "desktop", play: Boolean = true) =
        AccountsHarness(baseUrl = server.url("/").toString(), store = store, web = web, desktop = desktop, playAvailable = play)

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    /** Plays the owner in the browser: waits for Google's page, then pastes the address it would end on. Returns the page. */
    private suspend fun finishInBrowser(h: AccountsHarness, before: Int): okhttp3.HttpUrl {
        withTimeout(5_000) { while (!h.google.waiting.value || synchronized(h.opened) { h.opened.size } == before) delay(10) }
        val page = synchronized(h.opened) { h.opened.last() }.toHttpUrl()
        h.google.paste("${page.queryParameter("redirect_uri")}/?state=${page.queryParameter("state")}&code=4/code")
        return page
    }

    private fun only(h: AccountsHarness) = h.accounts.accounts.value.single()

    @Test fun `the choice rule`() {
        fun choose(browserSetUp: Boolean = true, web: Boolean = true, play: Boolean = true, token: Boolean = false, account: Boolean = false) =
            GoogleAccounts.chooseBrowser(browserSetUp, web, play, token, account)
        assertFalse("Play services by default", choose())
        assertTrue("Play services unavailable", choose(play = false))
        assertTrue("a browser refresh token is stored for the account", choose(token = true))
        assertTrue("the account signed in through the browser", choose(account = true))
        assertTrue("no Web client ID: the browser is the only way", choose(web = false))
        assertFalse("never the browser when it isn't set up", choose(browserSetUp = false, play = false, token = true, account = true))
    }

    @Test fun `with Play services Add account uses Play`() = runBlocking {
        val h = harness()
        h.accounts.addGoogle(Activity())
        assertEquals(1, h.signIn.signIns)
        assertEquals(AccountState.Connected, only(h).second)
        assertEquals(Account.SIGN_IN_PLAY, only(h).first.signIn)
        assertTrue(h.opened.isEmpty())
    }

    @Test fun `without Play services Add account opens the browser with Google's account chooser`() = runBlocking {
        val h = harness(play = false)
        server.enqueue(json(BrowserOAuthTest.tokens(access = "ya29.browser")))
        val add = async(Dispatchers.IO) { h.accounts.addGoogle(Activity()) }
        val page = finishInBrowser(h, 0)
        add.await()
        assertEquals("select_account consent", page.queryParameter("prompt"))
        assertNull(page.queryParameter("login_hint"))
        assertEquals(0, h.signIn.signIns)
        assertTrue(h.play.asked.isEmpty())
        val (account, state) = only(h)
        assertEquals(AccountState.Connected, state)
        assertEquals(Account.SIGN_IN_BROWSER, account.signIn)
        assertEquals("1//refresh", h.secrets.get(AccountSecrets.refreshToken(account.id)))
    }

    @Test fun `Use a browser instead forces the browser, then disconnect revokes and deletes`() = runBlocking {
        val h = harness()
        server.enqueue(json(BrowserOAuthTest.tokens(access = "ya29.browser")))
        val add = async(Dispatchers.IO) { h.accounts.addGoogle(Activity(), useBrowser = true) }
        finishInBrowser(h, 0)
        add.await()
        assertEquals(0, h.signIn.signIns)
        val id = only(h).first.id
        assertEquals(AccountState.Connected, only(h).second)
        val auth = (h.accounts.connection(id) as GoogleConnection).auth
        assertEquals("tools get the browser's token", Outcome.Success("ya29.browser"), auth.accessToken(setOf(GoogleScope.CalendarEvents)))
        assertTrue(h.play.asked.isEmpty())
        server.takeRequest()   // the exchange

        server.enqueue(MockResponse.Builder().code(200).build())
        h.accounts.disconnect(id)
        assertEquals("/revoke", server.takeRequest().url.encodedPath)
        assertNull(h.secrets.get(AccountSecrets.refreshToken(id)))
        assertTrue("Play's access isn't touched", h.play.revoked.isEmpty())
        assertEquals("Play services' sign-in isn't cleared for a browser account", 0, h.signIn.signOuts)
        assertTrue(h.accounts.accounts.value.isEmpty())
    }

    @Test fun `adding the same account again through the browser replaces its token, no duplicate`() = runBlocking {
        val store = InMemoryAccountStore(listOf(Account("a1", Provider.Google, "owner@gmail.com", "personal", Account.SIGN_IN_BROWSER)))
        val h = harness(store = store)
        h.secrets.put(AccountSecrets.refreshToken("a1"), "1//old")
        server.enqueue(json(BrowserOAuthTest.tokens(refresh = "1//new")))
        val add = async(Dispatchers.IO) { h.accounts.addGoogle(Activity(), useBrowser = true) }
        finishInBrowser(h, 0)
        add.await()
        assertEquals(listOf("a1"), h.accounts.accounts.value.map { it.first.id })
        assertEquals("personal", only(h).first.label)
        assertEquals("1//new", h.secrets.get(AccountSecrets.refreshToken("a1")))
    }

    @Test fun `an expired browser grant shows Sign in again, which signs that account in through the browser`() = runBlocking {
        val store = InMemoryAccountStore(listOf(Account("a1", Provider.Google, "owner@gmail.com", "owner@gmail.com", Account.SIGN_IN_BROWSER)))
        val h = harness(store = store)
        h.secrets.put(AccountSecrets.refreshToken("a1"), "1//expired")
        server.enqueue(json("""{"error":"invalid_grant"}""", 400))
        h.accounts.load()
        withTimeout(5_000) { while (only(h).second == AccountState.Checking) delay(10) }
        assertEquals(AccountState.NeedsSignIn(GoogleAccountAuth.SIGN_IN_AGAIN, viaBrowser = true), only(h).second)

        server.enqueue(json(BrowserOAuthTest.tokens(access = "ya29.again")))
        val grant = async(Dispatchers.IO) { h.accounts.grant("a1", Activity()) { error("no consent screen on the browser path") } }
        val page = finishInBrowser(h, 0)
        grant.await()
        assertEquals("owner@gmail.com", page.queryParameter("login_hint"))
        assertEquals(AccountState.Connected, only(h).second)
        assertEquals(0, h.signIn.signIns)
    }

    @Test fun `no network at startup keeps the grant and offers Try again`() = runBlocking {
        val store = InMemoryAccountStore(listOf(Account("a1", Provider.Google, "owner@gmail.com", "owner@gmail.com", Account.SIGN_IN_BROWSER)))
        val h = harness(store = store)
        h.secrets.put(AccountSecrets.refreshToken("a1"), "1//kept")
        server.close()
        h.accounts.load()
        withTimeout(5_000) { while (only(h).second == AccountState.Checking) delay(10) }
        assertEquals(AccountState.Error(BrowserGoogle.NO_NETWORK), only(h).second)
        assertEquals("1//kept", h.secrets.get(AccountSecrets.refreshToken("a1")))
    }

    @Test fun `Google is not set up only when both client IDs are missing`() {
        assertTrue(harness(web = "", desktop = "").google.notSetUp)
        assertFalse("Desktop only: the browser", harness(web = "").google.notSetUp)
        assertFalse("Web only: Play services", harness(desktop = "").google.notSetUp)
        assertFalse(harness(desktop = "").google.browserAvailable)
    }
}

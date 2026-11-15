package com.edith.app.alexa

import com.edith.app.home.ServiceState
import com.edith.app.home.SignInResult
import com.edith.app.testing.FakeClock
import com.edith.app.testing.InMemorySecretStore
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Found on the owner's phone (Andarium): signing in crashed the whole app the moment Edith tried to finish. Whatever the web view
 * hands back, and whatever Amazon answers, finishing a sign-in must end in a message, never an exception.
 */
class AlexaSignInRobustnessTest {
    private val amazon = FakeAmazon()
    private val secrets = InMemorySecretStore()
    private val clock = FakeClock(Instant.parse("2026-11-15T15:00:00Z"))
    private fun service() = AlexaService(secrets, amazon.api(), clock)
    @After fun tearDown() = amazon.shutdown()

    private suspend fun finish(s: AlexaService, cookies: String): SignInResult {
        val page = s.pageSignIn.begin()
        amazon.challenge = page.url.toHttpUrl().queryParameter("openid.oa2.code_challenge")
        return s.pageSignIn.finish(maplanding(), cookies)
    }

    @Test fun `cookies with characters a header can't carry don't crash the sign-in`() = runBlocking {
        // A newline, a control character and a non-ASCII letter: what an unusual web view might return.
        for (cookies in listOf("a=1\nb=2", "session-id=abc\u0001def; ok=1", "x-main=caf\u00e9; ok=1", "tab\t=1; ok=\u4f4f", "a=1\r\nHost: evil")) {
            val s = service()
            val r = finish(s, cookies)
            assertEquals(cookies, SignInResult.Done, r)
            assertEquals(ServiceState.Connected, s.state.value)
        }
    }

    @Test fun `the cookies that can be sent are still sent, and the others are left out`() = runBlocking {
        val s = service()
        finish(s, "good=1\nbad=\u00e9; fine=2")
        val sent = amazon.requests.first { it.url.encodedPath == "/auth/register" }.headers["Cookie"].orEmpty()
        assertTrue(sent, "fine=2" in sent && "frc=" in sent)
        assertFalse(sent, "\u00e9" in sent || '\n' in sent)
    }

    @Test fun `an answer that is not what Amazon usually sends is a message, not a crash`() = runBlocking {
        for (answer in listOf("", "null", "[]", "<html>blocked</html>", """{"response":{"success":{"tokens":{"bearer":"nope"}}}}""", """{"response":[1,2]}""")) {
            amazon.overrides["/auth/register"] = { mockwebserver3.MockResponse.Builder().code(200).body(answer).build() }
            val r = finish(service(), "session-id=x")
            assertTrue("$answer -> $r", r is SignInResult.Failed)
        }
    }
}

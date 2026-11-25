package com.edith.app.google.browser

import com.edith.app.core.util.RandomSource
import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertTrue
import com.edith.app.testing.testDispatchers
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test

/** K4.4: the iPhone's sign-in over a custom-scheme redirect, with a pretend web sheet and a pretend token endpoint. */
@OptIn(ExperimentalEncodingApi::class)
class AppSignInTest {
    private val redirect = "com.example.app:/oauth2redirect"
    private val requests = mutableListOf<String>()

    private fun idToken(email: String): String {
        val payload = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode("""{"email":"$email"}""".encodeToByteArray())
        return "e30.$payload.sig"
    }

    private fun flow(status: HttpStatusCode = HttpStatusCode.OK, body: String? = null, session: AuthSession): AppSignIn {
        val engine = MockEngine { request ->
            requests += (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
            respond(
                body ?: """{"access_token":"at","expires_in":3600,"refresh_token":"rt","id_token":"${idToken("me@example.com")}"}""",
                status,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val oauth = BrowserOAuth(lazy { HttpClient(engine) }, testDispatchers(), "client-id", random = RandomSource { b -> b.fill(7) })
        return AppSignIn(oauth, session, redirect)
    }

    private fun stateOf(url: String) = Url(url).parameters["state"]!!

    @Test fun signsInThroughTheSheetAndTradesTheCode() = runTest {
        var shownUrl = ""
        var shownScheme = ""
        val signIn = flow {url, scheme ->
            shownUrl = url; shownScheme = scheme
            AuthSessionResult.Callback("$redirect?code=abc&state=${stateOf(url)}")
        }
        val result = signIn.signIn(loginHint = "me@example.com")
        assertTrue(result is AppSignIn.Result.Tokens)
        val tokens = (result as AppSignIn.Result.Tokens).tokens
        assertEquals("rt", tokens.refreshToken)
        assertEquals("me@example.com", tokens.email)
        assertEquals("com.example.app", shownScheme)
        val page = Url(shownUrl)
        assertEquals(redirect, page.parameters["redirect_uri"])
        assertEquals("S256", page.parameters["code_challenge_method"])
        assertEquals("me@example.com", page.parameters["login_hint"])
        val sent = requests.single()
        assertTrue(sent.contains("code=abc"))
        assertTrue(sent.contains("code_verifier="))
        assertTrue(sent.contains("grant_type=authorization_code"))
    }

    @Test fun anAddressWithTheWrongStateIsNeverAccepted() = runTest {
        val signIn = flow { _, _ -> AuthSessionResult.Callback("$redirect?code=abc&state=forged") }
        assertEquals(AppSignIn.Result.Failed(AppSignIn.Why.WrongAddress), signIn.signIn())
        assertTrue(requests.isEmpty())
    }

    @Test fun anAddressInAnotherSchemeIsNeverAccepted() = runTest {
        val signIn = flow { url, _ -> AuthSessionResult.Callback("https://evil.example/?code=abc&state=${stateOf(url)}") }
        assertEquals(AppSignIn.Result.Failed(AppSignIn.Why.WrongAddress), signIn.signIn())
        assertTrue(requests.isEmpty())
    }

    @Test fun closingTheSheetCancels() = runTest {
        assertEquals(AppSignIn.Result.Cancelled, flow { _, _ -> AuthSessionResult.Cancelled }.signIn())
        assertTrue(requests.isEmpty())
    }

    @Test fun decliningIsReported() = runTest {
        val signIn = flow { url, _ -> AuthSessionResult.Callback("$redirect?error=access_denied&state=${stateOf(url)}") }
        assertEquals(AppSignIn.Result.Failed(AppSignIn.Why.Declined), signIn.signIn())
    }

    @Test fun aSheetThatCannotBeShownFails() = runTest {
        val r = flow { _, _ -> AuthSessionResult.Failed("no window") }.signIn()
        assertEquals(AppSignIn.Result.Failed(AppSignIn.Why.SheetFailed("no window")), r)
    }

    @Test fun anExpiredCodeIsReported() = runTest {
        val signIn = flow(HttpStatusCode.BadRequest, """{"error":"invalid_grant"}""") { url, _ -> AuthSessionResult.Callback("$redirect?code=abc&state=${stateOf(url)}") }
        assertEquals(AppSignIn.Result.Failed(AppSignIn.Why.CodeExpired), signIn.signIn())
    }

    @Test fun hostStyleCallbacksAreReadToo() = runTest {
        val signIn = flow { url, _ -> AuthSessionResult.Callback("com.example.app://auth?code=abc&state=${stateOf(url)}") }
        assertTrue(signIn.signIn() is AppSignIn.Result.Tokens)
    }

    @Test fun withoutAClientIdNothingStarts() = runTest {
        val oauth = BrowserOAuth(lazy { HttpClient(MockEngine { respond("") }) }, testDispatchers(), "")
        var shown = false
        val signIn = AppSignIn(oauth, { _, _ -> shown = true; AuthSessionResult.Cancelled }, redirect)
        assertEquals(AppSignIn.Result.Failed(AppSignIn.Why.NotSetUp), signIn.signIn())
        assertTrue(!shown)
    }
}

package com.edith.app.alexa

import com.edith.app.alexa.FakeAmazon.Companion.cap
import com.edith.app.alexa.FakeAmazon.Companion.endpoint
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/** C30 §1: Amazon's sign-in page, registering as an Alexa "device", the cookie exchange and the smart-home calls. */
class AlexaApiTest {
    private val amazon = FakeAmazon()
    @After fun tearDown() = amazon.shutdown()

    private val site = AmazonSite.DEFAULT

    private fun challengeOf(start: AlexaSignInStart) = start.url.toHttpUrl().queryParameter("openid.oa2.code_challenge")

    @Test fun `the sign-in page is the Alexa app's, with a PKCE challenge that matches the verifier and a fresh device each time`() {
        val api = amazon.api()
        val a = api.start(site)
        val url = a.url.toHttpUrl()
        assertEquals("www.amazon.com", url.host)
        assertEquals("/ap/signin", url.encodedPath)
        assertEquals("https://www.amazon.com/ap/maplanding", url.queryParameter("openid.return_to"))
        assertEquals("amzn_dp_project_dee_ios", url.queryParameter("openid.assoc_handle"))
        assertEquals("device:${a.deviceId}", url.queryParameter("openid.oa2.client_id"))
        assertEquals("S256", url.queryParameter("openid.oa2.code_challenge_method"))
        assertEquals("code", url.queryParameter("openid.oa2.response_type"))
        assertEquals("en_US", url.queryParameter("language"))
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(a.verifier.toByteArray()))
        assertEquals(expected, challengeOf(a))
        assertTrue("the device id ends with the Alexa app's device type", a.deviceId.endsWith("23413249564c5635564d32573831"))
        assertEquals(64 + 28, a.deviceId.length)
        val b = api.start(site)
        assertNotEquals(a.deviceId, b.deviceId); assertNotEquals(a.verifier, b.verifier); assertNotEquals(a.frc, b.frc)
    }

    @Test fun `other countries use their own handle and language`() {
        val uk = amazon.api().start(AmazonSite.of("amazon.co.uk")!!).url.toHttpUrl()
        assertEquals("www.amazon.co.uk", uk.host)
        assertEquals("amzn_dp_project_dee_ios_uk", uk.queryParameter("openid.assoc_handle"))
        assertEquals("en_GB", uk.queryParameter("language"))
    }

    @Test fun `the authorization code is read from where the sign-in ends, however it was pasted`() {
        val api = amazon.api()
        assertEquals("CODE-1", api.authorizationCode(maplanding("CODE-1")))
        assertEquals("CODE-2", api.authorizationCode("  ${maplanding("CODE-2")}  \n"))
        assertEquals("a b+c", api.authorizationCode(maplanding("a%20b%2Bc")))
        assertEquals("a pasted address with no scheme still works", "CODE-3", api.authorizationCode("www.amazon.com/ap/maplanding?openid.oa2.authorization_code=CODE-3&x=1"))
        assertNull(api.authorizationCode("https://www.amazon.com/ap/signin?openid.oa2.authorization_code=X"))
        assertNull(api.authorizationCode("https://www.amazon.com/ap/maplanding?openid.mode=cancel"))
        assertNull(api.authorizationCode("https://www.amazon.com/ap/maplanding?openid.oa2.authorization_code="))
        assertNull(api.authorizationCode("not an address"))
        assertTrue(api.isFinish(maplanding())); assertTrue(!api.isFinish("https://www.amazon.com/ap/signin"))
    }

    @Test fun `registering sends the code and the verifier, and returns the refresh token`() = runBlocking {
        val api = amazon.api()
        val start = api.start(site)
        amazon.challenge = challengeOf(start)
        val r = api.register(start, "AUTHCODE-1", "session-id=abc; ubid-main=u1") as AlexaReply.Ok
        assertEquals("refresh-1", r.value.refreshToken)
        assertEquals(32, r.value.deviceSerial.length)
        val sent = amazon.registerBodies.single()
        val auth = sent["auth_data"]!!.jsonObject
        assertEquals(start.deviceId, auth["client_id"]!!.jsonPrimitive.content)
        assertEquals(start.verifier, auth["code_verifier"]!!.jsonPrimitive.content)
        assertEquals("DeviceLegacy", auth["client_domain"]!!.jsonPrimitive.content)
        assertEquals("A2IVLV5VM2W81", sent["registration_data"]!!.jsonObject["device_type"]!!.jsonPrimitive.content)
        assertEquals("Edith", sent["registration_data"]!!.jsonObject["app_name"]!!.jsonPrimitive.content)
        assertEquals(start.frc, sent["user_context_map"]!!.jsonObject["frc"]!!.jsonPrimitive.content)
        val names = sent["cookies"]!!.jsonObject["website_cookies"].toString()
        assertTrue(names, "session-id" in names && "ubid-main" in names && "frc" in names && "map-md" in names)
        assertEquals("api.amazon.com", amazon.requests.single().headers["x-amzn-identity-auth-domain"])
    }

    @Test fun `registering from the browser has no cookies of the page's, and still works`() = runBlocking {
        val api = amazon.api()
        val start = api.start(site)
        amazon.challenge = challengeOf(start)
        assertTrue(api.register(start, "AUTHCODE-1", "") is AlexaReply.Ok)
        val names = amazon.registerBodies.single()["cookies"]!!.jsonObject["website_cookies"].toString()
        assertTrue("only the two Edith makes up: $names", "frc" in names && "map-md" in names && "session-id" !in names)
    }

    @Test fun `a wrong code or verifier is Rejected, and an unreachable Amazon is a failure`() = runBlocking {
        val api = amazon.api()
        val start = api.start(site)
        amazon.challenge = challengeOf(start)
        assertEquals(AlexaReply.Rejected, api.register(start, "WRONG", ""))
        amazon.challenge = "not-the-challenge"
        assertEquals(AlexaReply.Rejected, api.register(start, "AUTHCODE-1", ""))
        amazon.shutdown()
        assertTrue(api.register(start, "AUTHCODE-1", "") is AlexaReply.Failed)
    }

    @Test fun `cookies and the csrf token come from the refresh token, and nothing else is sent`() = runBlocking {
        amazon.signedInBefore()
        val s = (amazon.api().session(site, "refresh-1") as AlexaReply.Ok).value
        assertEquals("CSRF1", s.csrf)
        assertTrue(s.cookie, "at-main=AT1" in s.cookie && "csrf=CSRF1" in s.cookie)
        val exchange = amazon.requests.first { it.url.encodedPath == "/ap/exchangetoken/cookies" }
        assertEquals("", exchange.headers["Cookie"].orEmpty())
        assertTrue(exchange.body!!.utf8().contains("source_token=refresh-1"))
    }

    @Test fun `a refresh token Amazon refuses is Rejected, and no csrf token is a failure`() = runBlocking {
        assertEquals(AlexaReply.Rejected, amazon.api().session(site, "never-issued"))
        amazon.signedInBefore(); amazon.csrfOnLanguagePage = false
        val r = amazon.api().session(site, "refresh-1")
        assertTrue(r.toString(), r is AlexaReply.Failed && "csrf" in r.why)
        assertTrue("the other pages were tried too", amazon.count("/spa/index.html") >= 1)
    }

    @Test fun `the device list and the states use the cookies and csrf, and old cookies are Rejected`() = runBlocking {
        amazon.signedInBefore()
        amazon.endpointItems = FakeAmazon.items(endpoint("e1", "Lamp", "LIGHT", applianceId = "a1", retrievable = listOf("Alexa.PowerController" to "powerState")))
        amazon.states = mapOf("a1" to listOf(cap("Alexa.PowerController", "powerState", "ON")))
        val api = amazon.api()
        val s = (api.session(site, "refresh-1") as AlexaReply.Ok).value
        val items = (api.endpoints(site, s) as AlexaReply.Ok).value
        val list = AlexaParse.parseEndpoints(items)
        assertEquals(listOf("Lamp"), list.map { it.name })
        val graphql = amazon.requests.last { it.url.encodedPath == "/nexus/v1/graphql" }
        assertEquals("CSRF1", graphql.headers["csrf"])
        assertTrue(graphql.body!!.utf8().contains("query Endpoints"))
        val answer = (api.states(site, s, AlexaParse.stateRequests(list), 5) as AlexaReply.Ok).value
        assertEquals(true, AlexaParse.parseStates(answer)["a1"]!!.state!!.on)
        amazon.expireCookies()
        assertEquals(AlexaReply.Rejected, api.endpoints(site, s))
        assertEquals(AlexaReply.Rejected, api.states(site, s, AlexaParse.stateRequests(list), 5))
    }

    @Test fun `a redirect to Amazon's sign-in page means the cookies are no good, and a redirect to cleartext is refused`() = runBlocking {
        amazon.signedInBefore()
        val api = amazon.api()
        val s = (api.session(site, "refresh-1") as AlexaReply.Ok).value
        amazon.overrides["/nexus/v1/graphql"] = { MockResponse.Builder().code(302).addHeader("Location", "/ap/signin?x=1").build() }
        assertEquals(AlexaReply.Rejected, api.endpoints(site, s))
        amazon.overrides["/nexus/v1/graphql"] = { MockResponse.Builder().code(302).addHeader("Location", "http://evil.example/steal").build() }
        assertTrue(api.endpoints(site, s) is AlexaReply.Failed)
    }

    @Test fun `an answer that isn't the device list is a failure, not a crash`() = runBlocking {
        amazon.signedInBefore()
        val api = amazon.api()
        val s = (api.session(site, "refresh-1") as AlexaReply.Ok).value
        amazon.overrides["/nexus/v1/graphql"] = { MockResponse.Builder().code(200).body("<html>nope</html>").build() }
        assertTrue(api.endpoints(site, s) is AlexaReply.Failed)
        amazon.overrides["/nexus/v1/graphql"] = { MockResponse.Builder().code(500).build() }
        assertTrue(api.endpoints(site, s) is AlexaReply.Failed)
    }
}

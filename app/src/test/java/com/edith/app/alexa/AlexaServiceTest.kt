package com.edith.app.alexa

import com.edith.app.alexa.FakeAmazon.Companion.cap
import com.edith.app.alexa.FakeAmazon.Companion.endpoint
import com.edith.app.core.error.Outcome
import com.edith.app.home.DeviceType
import com.edith.app.home.ServiceState
import com.edith.app.home.SignInResult
import com.edith.app.testing.FakeClock
import com.edith.app.testing.InMemorySecretStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** C30 part 1: sign-in on Amazon's page, the device list and states, expired cookies, a dead token, last-known states. */
class AlexaServiceTest {
    private val amazon = FakeAmazon()
    private val secrets = InMemorySecretStore()
    private val clock = FakeClock(Instant.parse("2026-11-14T15:00:00Z"))
    private fun service() = AlexaService(secrets, amazon.api(), clock)
    @After fun tearDown() = amazon.shutdown()

    private val power = "Alexa.PowerController" to "powerState"

    private val home get() = FakeAmazon.items(
        endpoint("e1", "Living room lamp", "LIGHT", applianceId = "a1", retrievable = listOf(power, "Alexa.BrightnessController" to "brightness")),
        endpoint("e2", "Front door", "SMARTLOCK", applianceId = "a2", maker = "August", retrievable = listOf("Alexa.LockController" to "lockState")),
        endpoint("e3", "Hall thermostat", "THERMOSTAT", applianceId = "a3", maker = "Ecobee", retrievable = listOf("Alexa.ThermostatController" to "targetSetpoint", "Alexa.TemperatureSensor" to "temperature")),
        endpoint("e4", "Porch camera", "CAMERA", applianceId = "a4"),
        endpoint("e5", "Kitchen Echo", "ALEXA_VOICE_ENABLED"),
    )

    private val answers = mapOf(
        "a1" to listOf(cap("Alexa.PowerController", "powerState", "ON"), cap("Alexa.BrightnessController", "brightness", 40)),
        "a2" to listOf(cap("Alexa.LockController", "lockState", "LOCKED")),
        "a3" to listOf(cap("Alexa.ThermostatController", "targetSetpoint", FakeAmazon.temp(70, "FAHRENHEIT")), cap("Alexa.TemperatureSensor", "temperature", FakeAmazon.temp(21, "CELSIUS"))),
    )

    /** Signs in the way the app does: begin, then finish with where Amazon's page ended. */
    private suspend fun signIn(s: AlexaService, cookies: String = "session-id=x"): SignInResult {
        val page = s.pageSignIn.begin()
        amazon.challenge = page.url.toHttpUrl().queryParameter("openid.oa2.code_challenge")
        return s.pageSignIn.finish(maplanding(), cookies)
    }

    private suspend fun signedIn(): AlexaService {
        val s = service()
        assertEquals(SignInResult.Done, signIn(s))
        amazon.endpointItems = home; amazon.states = answers
        return s
    }

    @Test fun `the sign-in page is begun, the web view's start is described, and finishing keeps only the refresh token`() = runBlocking {
        val s = service()
        val page = s.pageSignIn.begin()
        assertTrue(page.url.startsWith("https://www.amazon.com/ap/signin?"))
        assertEquals("https://www.amazon.com", page.cookieSite)
        assertEquals(setOf("frc", "map-md"), page.cookies.keys)
        assertTrue(page.isFinish(maplanding())); assertFalse(page.isFinish(page.url))
        amazon.challenge = page.url.toHttpUrl().queryParameter("openid.oa2.code_challenge")
        assertEquals(SignInResult.Done, s.pageSignIn.finish(maplanding(), "session-id=abc"))
        assertEquals(ServiceState.Connected, s.state.value)
        val kept = secrets.get(AlexaService.SECRET_KEY)!!
        assertTrue(kept, "refresh-1" in kept && "amazon.com" in kept)
        assertFalse("no cookie and no code is kept", "session-id" in kept || "AUTHCODE" in kept || "abc" in kept)
        assertNull("the service has no form: it signs in on Amazon's page", s.form)
    }

    @Test fun `finishing without beginning, or with the wrong address, says what is wrong`() = runBlocking {
        val s = service()
        val early = s.pageSignIn.finish(maplanding(), "") as SignInResult.Failed
        assertTrue(early.message, "expired" in early.message)
        s.pageSignIn.begin()
        val wrong = s.pageSignIn.finish("https://www.amazon.com/ap/signin", "") as SignInResult.Failed
        assertTrue(wrong.message, "maplanding" in wrong.message)
        assertEquals(ServiceState.NotConnected, s.state.value)
        assertTrue(s.signIn(emptyMap()) is SignInResult.Failed)
    }

    @Test fun `Amazon refusing the code is said in words, and signing in can be tried again`() = runBlocking {
        val s = service()
        val page = s.pageSignIn.begin()
        amazon.challenge = page.url.toHttpUrl().queryParameter("openid.oa2.code_challenge")
        val refused = s.pageSignIn.finish(maplanding("OLD-CODE"), "") as SignInResult.Failed
        assertTrue(refused.message, "didn't accept" in refused.message)
        assertEquals(SignInResult.Done, s.pageSignIn.finish(maplanding(), ""))
    }

    @Test fun `the browser way - the address is pasted and there are no cookies`() = runBlocking {
        val s = service()
        assertEquals(SignInResult.Done, signIn(s, cookies = ""))
        assertEquals(ServiceState.Connected, s.state.value)
    }

    @Test fun `devices come with their states - lights, locks and a thermostat, not cameras' states or Echo speakers`() = runBlocking {
        val s = signedIn()
        val list = (s.backend.devices() as Outcome.Success).value
        assertEquals("by category, then name", listOf("Porch camera", "Living room lamp", "Front door", "Hall thermostat"), list.map { it.name })
        val lamp = list.first { it.name == "Living room lamp" }
        assertEquals(DeviceType.Light, lamp.type)
        assertEquals(true, lamp.state!!.on); assertEquals(40, lamp.state!!.brightness)
        assertEquals(DeviceType.Lock, list.first { it.name == "Front door" }.type)
        assertEquals(DeviceType.Camera, list.first { it.name == "Porch camera" }.type)
        val t = list.first { it.type == DeviceType.Thermostat }.state!!
        assertEquals(70.0, t.setpoint!!, 0.001)
        assertEquals("the sensor's °C reading is shown in the account's °F", 69.8, t.temperature!!, 0.001)
        assertTrue("devices are reported through Alexa", list.all { it.backend == "alexa" })
        assertTrue(s.backend.isHub)
        assertTrue("no pictures from Alexa", s.backend.snapshot("e4") is Outcome.Failure)
        assertEquals("read-only for now: nothing is offered to do", emptySet<Any>(), list.flatMap { it.capabilities }.toSet())
    }

    @Test fun `the cookies are fetched once for many calls at once`() = runBlocking {
        val s = signedIn()
        val results = (1..5).map { async { s.backend.devices() } }.awaitAll()
        assertTrue(results.all { it is Outcome.Success })
        assertEquals(1, amazon.count("/ap/exchangetoken/cookies"))
    }

    @Test fun `cookies Amazon has expired are fetched again once and the call is asked again`() = runBlocking {
        val s = signedIn()
        assertTrue(s.backend.devices() is Outcome.Success)
        amazon.expireCookies()
        assertTrue(s.backend.devices() is Outcome.Success)
        assertEquals(2, amazon.count("/ap/exchangetoken/cookies"))
        assertEquals(ServiceState.Connected, s.state.value)
    }

    @Test fun `a refresh token Amazon no longer accepts means Sign in again`() = runBlocking {
        signedIn()
        amazon.validRefresh.clear()
        val later = service().also { it.load() }
        val r = later.backend.devices() as Outcome.Failure
        assertTrue(r.error.userMessage, "signing in again" in r.error.userMessage)
        assertEquals(ServiceState.NeedsSignIn, later.state.value)
    }

    @Test fun `an Amazon that can't be reached does not sign the owner out`() = runBlocking {
        signedIn()
        val later = service().also { it.load() }
        amazon.shutdown()
        assertTrue(later.backend.devices() is Outcome.Failure)
        assertEquals(ServiceState.Connected, later.state.value)
    }

    @Test fun `a saved sign-in is picked up at the next start, and disconnect forgets it`() = runBlocking {
        signedIn()
        val later = service().also { it.load() }
        assertEquals(ServiceState.Connected, later.state.value)
        amazon.endpointItems = home; amazon.states = answers
        assertTrue(later.backend.devices() is Outcome.Success)
        later.disconnect()
        assertNull(secrets.get(AlexaService.SECRET_KEY))
        assertEquals(ServiceState.NotConnected, later.state.value)
        assertTrue(later.backend.devices() is Outcome.Failure)
    }

    @Test fun `a batch that gives no answer keeps each device's last state, shown with its age`() = runBlocking {
        val s = signedIn()
        val first = (s.backend.devices() as Outcome.Success).value.first { it.name == "Front door" }
        assertEquals(clock.now().toEpochMilli(), first.stateAt)
        clock.advance(Duration.ofMinutes(7))
        amazon.overrides["/api/phoenix/state"] = { MockResponse.Builder().code(500).build() }
        val again = (s.backend.devices() as Outcome.Success).value.first { it.name == "Front door" }
        assertNotNull(again.state)
        assertTrue("last known, not just now", again.stateAt!! < clock.now().toEpochMilli())
    }

    @Test fun `an unreachable device is shown as not responding`() = runBlocking {
        val s = signedIn()
        amazon.states = answers - "a1"; amazon.stateErrors = mapOf("a1" to "ENDPOINT_UNREACHABLE")
        val lamp = (s.backend.devices() as Outcome.Success).value.first { it.name == "Living room lamp" }
        assertFalse(lamp.online)
    }

    @Test fun `a slow Amazon blanks only its batch and doesn't hang the list`() = runBlocking {
        val s = signedIn()
        amazon.overrides["/api/phoenix/state"] = { MockResponse.Builder().code(500).build() }
        val list = (s.backend.devices() as Outcome.Success).value
        assertEquals("every device is still listed", 4, list.size)
        assertTrue(list.all { it.state == null })
    }

    @Test fun `commands are not offered yet, and a command that arrives anyway is refused`() = runBlocking {
        val s = signedIn()
        assertTrue(s.backend.command("e1", com.edith.app.home.DeviceCommand.TurnOn) is Outcome.Failure)
    }

    @Test fun `category codes map to Edith's device types`() {
        assertEquals(DeviceType.Plug, AlexaService.typeOf("SMARTPLUG"))
        assertEquals(DeviceType.TemperatureSensor, AlexaService.typeOf("TEMPERATURE_SENSOR"))
        assertEquals(DeviceType.Sensor, AlexaService.typeOf("CONTACT_SENSOR"))
        assertEquals(DeviceType.Other, AlexaService.typeOf("WASHER"))
    }
}

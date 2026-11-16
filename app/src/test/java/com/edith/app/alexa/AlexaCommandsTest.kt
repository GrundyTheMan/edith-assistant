package com.edith.app.alexa

import com.edith.app.alexa.FakeAmazon.Companion.cap
import com.edith.app.alexa.FakeAmazon.Companion.endpoint
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.home.Approval
import com.edith.app.home.Capability
import com.edith.app.home.DeviceCommand
import com.edith.app.home.DeviceState
import com.edith.app.home.HomeActions
import com.edith.app.home.HomeDevice
import com.edith.app.home.LockState
import com.edith.app.home.ServiceState
import com.edith.app.home.SignInResult
import com.edith.app.home.TempUnit
import com.edith.app.testing.FakeClock
import com.edith.app.testing.InMemorySecretStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** C30 part 2: what each device can be told, the requests sent, Amazon's answer checked, and the approval rules on top. */
class AlexaCommandsTest {
    private val amazon = FakeAmazon()
    private val clock = FakeClock(Instant.parse("2026-11-14T15:00:00Z"))
    private val secrets = InMemorySecretStore()
    private fun service() = AlexaService(secrets, amazon.api(), clock)
    @After fun tearDown() = amazon.shutdown()

    private val power = "Alexa.PowerController" to "powerState"

    private val home get() = FakeAmazon.items(
        endpoint("e1", "Living room lamp", "LIGHT", applianceId = "a1",
            actions = listOf("turnOn", "turnOff", "setBrightness", "setColor", "setColorTemperature"),
            retrievable = listOf(power, "Alexa.BrightnessController" to "brightness")),
        endpoint("e2", "Front door", "SMARTLOCK", applianceId = "a2", maker = "August", actions = listOf("lockAction"),
            retrievable = listOf("Alexa.LockController" to "lockState")),
        endpoint("e3", "Hall thermostat", "THERMOSTAT", applianceId = "a3", maker = "Ecobee",
            actions = listOf("setTargetTemperature", "setThermostatMode"), modes = listOf("heat", "cool", "auto"),
            retrievable = listOf("Alexa.ThermostatController" to "targetSetpoint", "Alexa.ThermostatController" to "thermostatMode")),
        endpoint("e4", "Porch camera", "CAMERA", applianceId = "a4", actions = listOf("turnOn")),
        endpoint("e5", "Bedroom fan", "FAN", applianceId = "a5", actions = listOf("turnOn", "turnOff", "setPercentage"),
            retrievable = listOf(power, "Alexa.PercentageController" to "percentage")),
        endpoint("e6", "Main garage", "GARAGE_DOOR", applianceId = "a6", actions = listOf("turnOn", "turnOff")),
        // Lists no control actions at all: what it reports (power) says what it can do.
        endpoint("e7", "Desk plug", "SMARTPLUG", applianceId = "a7", retrievable = listOf(power)),
        endpoint("e8", "Hall sensor", "TEMPERATURE_SENSOR", applianceId = "a8", retrievable = listOf("Alexa.TemperatureSensor" to "temperature")),
        // A thermostat that takes a setpoint but lists no modes to set.
        endpoint("e9", "Garage heater", "THERMOSTAT", applianceId = "a9", actions = listOf("setTargetTemperature"),
            retrievable = listOf("Alexa.ThermostatController" to "targetSetpoint")),
    )

    private fun states() = mapOf(
        "a1" to listOf(cap("Alexa.PowerController", "powerState", "OFF"), cap("Alexa.BrightnessController", "brightness", 40)),
        "a2" to listOf(cap("Alexa.LockController", "lockState", "UNLOCKED")),
        "a3" to listOf(cap("Alexa.ThermostatController", "targetSetpoint", FakeAmazon.temp(70, "FAHRENHEIT")), cap("Alexa.ThermostatController", "thermostatMode", "HEAT")),
        "a5" to listOf(cap("Alexa.PowerController", "powerState", "ON"), cap("Alexa.PercentageController", "percentage", 30)),
        "a7" to listOf(cap("Alexa.PowerController", "powerState", "ON")),
    )

    private suspend fun signedIn(): AlexaService {
        val s = service()
        val page = s.pageSignIn.begin()
        amazon.challenge = page.url.toHttpUrl().queryParameter("openid.oa2.code_challenge")
        assertEquals(SignInResult.Done, s.pageSignIn.finish(maplanding(), "session-id=x"))
        amazon.endpointItems = home; amazon.states = states()
        return s
    }

    private suspend fun devices(s: AlexaService): Map<String, HomeDevice> = (s.backend.devices() as Outcome.Success).value.associateBy { it.name }

    private fun sentParameters(i: Int = 0): JsonObject = amazon.controls[i]["parameters"]!!.jsonObject
    private fun fail(r: Outcome<DeviceState>): String = ((r as Outcome.Failure).error as AppError.ToolFailed).detail

    // ---- What each device can do ----

    @Test fun `each device offers what Alexa lists it can do`() = runBlocking {
        val d = devices(signedIn())
        assertEquals(setOf(Capability.OnOff, Capability.Brightness, Capability.Color(), Capability.WhiteTone()), d.getValue("Living room lamp").capabilities)
        assertEquals(setOf<Capability>(Capability.LockUnlock), d.getValue("Front door").capabilities)
        assertEquals(setOf(Capability.OnOff, Capability.Percentage), d.getValue("Bedroom fan").capabilities)
        val t = d.getValue("Hall thermostat").capability<Capability.Thermostat>()!!
        assertEquals(listOf("heat", "cool", "auto"), t.modes)
        assertEquals("a .com account is in °F", TempUnit.Fahrenheit, t.unit)
    }

    @Test fun `cameras, garage doors and sensors are listed but offer nothing to do`() = runBlocking {
        val d = devices(signedIn())
        assertEquals(emptySet<Capability>(), d.getValue("Porch camera").capabilities)
        assertEquals("Jarvis can't open a garage through Alexa either", emptySet<Capability>(), d.getValue("Main garage").capabilities)
        assertEquals(emptySet<Capability>(), d.getValue("Hall sensor").capabilities)
    }

    @Test fun `a device that lists no actions is offered what its state properties imply`() = runBlocking {
        assertEquals(setOf<Capability>(Capability.OnOff), devices(signedIn()).getValue("Desk plug").capabilities)
    }

    @Test fun `a thermostat that lists no mode action has a setpoint and no modes to set`() = runBlocking {
        val t = devices(signedIn()).getValue("Garage heater").capability<Capability.Thermostat>()!!
        assertEquals(emptyList<String>(), t.modes)
    }

    // ---- The requests ----

    @Test fun `turning a light on sends the request and answers what the device reports right after`() = runBlocking {
        val s = signedIn()
        amazon.onControl = { amazon.states = amazon.states + ("a1" to listOf(cap("Alexa.PowerController", "powerState", "ON"), cap("Alexa.BrightnessController", "brightness", 40))) }
        val r = s.backend.command("e1", DeviceCommand.TurnOn)
        assertEquals(true, (r as Outcome.Success).value.on)
        assertEquals(1, amazon.controls.size)
        assertEquals("e1", amazon.controls[0]["entityId"]!!.jsonPrimitive.content)
        assertEquals("APPLIANCE", amazon.controls[0]["entityType"]!!.jsonPrimitive.content)
        assertEquals("turnOn", sentParameters()["action"]!!.jsonPrimitive.content)
        assertEquals("a PUT, with the page's csrf header", "PUT", amazon.requests.last { it.url.encodedPath == "/api/phoenix/state" && it.method == "PUT" }.method)
    }

    @Test fun `brightness and fan speed go as fractions`() = runBlocking {
        val s = signedIn()
        s.backend.command("e1", DeviceCommand.SetBrightness(40))
        s.backend.command("e5", DeviceCommand.SetPercentage(60))
        assertEquals("setBrightness", sentParameters(0)["action"]!!.jsonPrimitive.content)
        assertEquals(0.4, sentParameters(0)["brightness"]!!.jsonPrimitive.double, 0.0001)
        assertEquals(0.6, sentParameters(1)["percentage"]!!.jsonPrimitive.double, 0.0001)
    }

    @Test fun `thermostat setpoint is in the account's scale and the mode is upper case`() = runBlocking {
        val s = signedIn()
        s.backend.command("e3", DeviceCommand.SetTemperature(72.0))
        s.backend.command("e3", DeviceCommand.SetMode("cool"))
        assertEquals("setTargetTemperature", sentParameters(0)["action"]!!.jsonPrimitive.content)
        assertEquals(72.0, sentParameters(0)["targetTemperature.value"]!!.jsonPrimitive.double, 0.0001)
        assertEquals("FAHRENHEIT", sentParameters(0)["targetTemperature.scale"]!!.jsonPrimitive.content)
        assertEquals("COOL", sentParameters(1)["thermostatMode.value"]!!.jsonPrimitive.content)
    }

    @Test fun `colours and whites go by name with underscores`() = runBlocking {
        val s = signedIn()
        s.backend.command("e1", DeviceCommand.SetColor("red"))
        s.backend.command("e1", DeviceCommand.SetWhite("warm white"))
        assertEquals("red", sentParameters(0)["colorName"]!!.jsonPrimitive.content)
        assertEquals("setColorTemperature", sentParameters(1)["action"]!!.jsonPrimitive.content)
        assertEquals("warm_white", sentParameters(1)["colorTemperatureName"]!!.jsonPrimitive.content)
    }

    @Test fun `locking and unlocking send the target state`() = runBlocking {
        val s = signedIn()
        s.backend.command("e2", DeviceCommand.Lock)
        s.backend.command("e2", DeviceCommand.Unlock)
        assertEquals("lockAction", sentParameters(0)["action"]!!.jsonPrimitive.content)
        assertEquals("LOCKED", sentParameters(0)["targetLockState.value"]!!.jsonPrimitive.content)
        assertEquals("UNLOCKED", sentParameters(1)["targetLockState.value"]!!.jsonPrimitive.content)
    }

    @Test fun `a lock's answer afterwards shows the new lock state`() = runBlocking {
        val s = signedIn()
        amazon.onControl = { amazon.states = amazon.states + ("a2" to listOf(cap("Alexa.LockController", "lockState", "LOCKED"))) }
        assertEquals(LockState.Locked, (s.backend.command("e2", DeviceCommand.Lock) as Outcome.Success).value.lock)
    }

    @Test fun `a cloud that still reports the old state is answered with it, for Home to ask again`() = runBlocking {
        val s = signedIn()
        val r = s.backend.command("e1", DeviceCommand.TurnOn)   // the pretend device doesn't change
        assertEquals(false, (r as Outcome.Success).value.on)
    }

    // ---- What can't be sent ----

    @Test fun `a command the device can't take is refused without asking Amazon`() = runBlocking {
        val s = signedIn()
        assertTrue(fail(s.backend.command("e5", DeviceCommand.SetBrightness(50))).contains("can't do that through Alexa"))
        assertTrue(s.backend.command("e4", DeviceCommand.TurnOn) is Outcome.Failure)
        assertTrue(s.backend.command("e6", DeviceCommand.Open) is Outcome.Failure)
        assertTrue(s.backend.command("e1", DeviceCommand.Open) is Outcome.Failure)
        assertTrue("a device that has gone", s.backend.command("nope", DeviceCommand.TurnOn) is Outcome.Failure)
        assertEquals(0, amazon.controls.size)
    }

    // ---- Amazon's answer ----

    @Test fun `Amazon answering 200 with a refusal is a failure, in words`() = runBlocking {
        val s = signedIn()
        amazon.controlResponse = """{"errors":[{"code":"INVALID_VALUE","message":"Out of range"}],"controlResponses":[]}"""
        assertEquals("Living room lamp: Alexa refused: INVALID_VALUE (Out of range).", fail(s.backend.command("e1", DeviceCommand.TurnOn)))
        amazon.controlResponse = """{"errors":[{"code":"ENDPOINT_UNREACHABLE"}]}"""
        assertEquals("Living room lamp: Alexa couldn't reach the device.", fail(s.backend.command("e1", DeviceCommand.TurnOn)))
        amazon.controlResponse = """{"controlResponses":[{"code":"FAILURE"}]}"""
        assertEquals("Living room lamp: Alexa answered FAILURE.", fail(s.backend.command("e1", DeviceCommand.TurnOn)))
    }

    @Test fun `an Amazon that can't be reached is said, and doesn't sign the owner out`() = runBlocking {
        val s = signedIn()
        amazon.overrides["/api/phoenix/state"] = { r -> if (r.method == "PUT") mockwebserver3.MockResponse.Builder().code(500).body("x").build() else mockwebserver3.MockResponse.Builder().code(200).body("{}").build() }
        val r = s.backend.command("e1", DeviceCommand.TurnOn)
        assertTrue(fail(r), "500" in fail(r))
        assertEquals(ServiceState.Connected, s.state.value)
    }

    @Test fun `cookies that expired are fetched again and the command is sent once`() = runBlocking {
        val s = signedIn()
        devices(s)
        amazon.expireCookies()
        assertTrue(s.backend.command("e1", DeviceCommand.TurnOn) is Outcome.Success)
        assertEquals("the refused try never reached the device", 1, amazon.controls.size)
    }

    @Test fun `a refresh token Amazon no longer accepts says Sign in again`() = runBlocking {
        val s = signedIn()
        devices(s)
        amazon.validRefresh.clear(); amazon.expireCookies()
        // The list is cached, so the first call that needs Amazon is the command itself.
        val r = s.backend.command("e1", DeviceCommand.TurnOn)
        assertTrue(fail(r).contains("signing in again"))
        assertEquals(ServiceState.NeedsSignIn, s.state.value)
        assertEquals(0, amazon.controls.size)
    }

    // ---- Parsing, on its own ----

    @Test fun `controlProblem reads Amazon's answers`() {
        val p = { text: String -> AlexaParse.controlProblem(kotlinx.serialization.json.Json.parseToJsonElement(text)) }
        assertNull(p("""{"controlResponses":[{"code":"SUCCESS"}],"errors":[]}"""))
        assertNull(p("{}"))
        assertNull(AlexaParse.controlProblem(null))
        assertEquals("Alexa answered DEVICE_BUSY.", p("""{"controlResponses":[{"code":"SUCCESS"},{"code":"DEVICE_BUSY"}]}"""))
        assertEquals("Alexa refused: ERROR.", p("""{"errors":[{}]}"""))
    }

    @Test fun `a Celsius account gets Celsius controls and a Celsius thermostat`() {
        val d = AlexaParse.parseEndpoints(FakeAmazon.items(endpoint("t", "Thermostat", "THERMOSTAT", actions = listOf("setTargetTemperature", "setThermostatMode")))).single()
        assertEquals(TempUnit.Celsius, AlexaParse.capabilitiesOf(d, TempScale.Celsius).filterIsInstance<Capability.Thermostat>().single().unit)
        assertEquals("CELSIUS", AlexaParse.controlParameters(DeviceCommand.SetTemperature(21.0), d, TempScale.Celsius)!!["targetTemperature.scale"]!!.jsonPrimitive.content)
        assertEquals("with no modes listed, Alexa's own five are offered", listOf("heat", "cool", "auto", "eco", "off"),
            AlexaParse.capabilitiesOf(d, TempScale.Celsius).filterIsInstance<Capability.Thermostat>().single().modes)
    }

    @Test fun `the older setLockState name works as lockAction`() {
        val d = AlexaParse.parseEndpoints(FakeAmazon.items(endpoint("l", "Back door", "SMARTLOCK", actions = listOf("setLockState")))).single()
        assertTrue(Capability.LockUnlock in AlexaParse.capabilitiesOf(d, TempScale.Fahrenheit))
        assertNotNull(AlexaParse.controlParameters(DeviceCommand.Lock, d, TempScale.Fahrenheit))
    }

    // ---- With Edith's own rules on top ----

    @Test fun `Edith's rules apply to Alexa devices - unlocking needs the screen, a big setpoint change a card`() = runBlocking {
        val d = devices(signedIn())
        val lock = d.getValue("Front door")
        assertEquals(Approval.ScreenOnly, HomeActions.approval(lock, DeviceCommand.Unlock))
        assertEquals(Approval.None, HomeActions.approval(lock, DeviceCommand.Lock))
        val t = d.getValue("Hall thermostat")
        assertEquals(Approval.None, HomeActions.approval(t, DeviceCommand.SetTemperature(73.0)))
        assertEquals(Approval.Card, HomeActions.approval(t, DeviceCommand.SetTemperature(80.0)))
        assertEquals(Approval.Card, HomeActions.approval(t, DeviceCommand.SetMode("cool")))
    }

    @Test fun `the tool's action names work on what Alexa offers, and say what a device can't do`() = runBlocking {
        val d = devices(signedIn())
        assertEquals(DeviceCommand.SetBrightness(40), HomeActions.parse(d.getValue("Living room lamp"), "set_brightness", "40").getOrThrow())
        assertEquals(DeviceCommand.SetMode("heat"), HomeActions.parse(d.getValue("Hall thermostat"), "set_thermostat_mode", "heat").getOrThrow())
        assertTrue(HomeActions.parse(d.getValue("Hall thermostat"), "set_thermostat_mode", "eco").isFailure)
        val garage = HomeActions.parse(d.getValue("Main garage"), "open", null)
        assertTrue(garage.isFailure); assertFalse(garage.exceptionOrNull()!!.message.isNullOrEmpty())
    }
}

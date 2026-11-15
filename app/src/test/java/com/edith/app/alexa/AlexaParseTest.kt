package com.edith.app.alexa

import com.edith.app.alexa.FakeAmazon.Companion.cap
import com.edith.app.alexa.FakeAmazon.Companion.endpoint
import com.edith.app.alexa.FakeAmazon.Companion.temp
import com.edith.app.home.DoorState
import com.edith.app.home.LockState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C30: reading Amazon's answers (ported from Jarvis's `shared/alexa.ts`), from made-up answers. */
class AlexaParseTest {
    private val power = "Alexa.PowerController" to "powerState"

    @Test fun `the device list - names, types, merged appliance ids, readable properties and thermostat modes`() {
        val list = AlexaParse.parseEndpoints(FakeAmazon.items(
            endpoint("e2", "Living room lamp", "LIGHT", applianceId = "a2", merged = listOf("a2b"), actions = listOf("turnOn", "setBrightness"), retrievable = listOf(power, "Alexa.BrightnessController" to "brightness")),
            endpoint("e1", "Front door", "SMARTLOCK", actions = listOf("lockAction"), retrievable = listOf("Alexa.LockController" to "lockState"), maker = "August"),
            endpoint("e3", "Hallway", "THERMOSTAT", modes = listOf("HEAT", "cool"), retrievable = listOf("Alexa.ThermostatController" to "targetSetpoint")),
        ))
        // Sorted by category, then name.
        assertEquals(listOf("Living room lamp", "Front door", "Hallway"), list.map { it.name })
        val lamp = list.first { it.entityId == "e2" }
        assertEquals(listOf("a2", "a2b"), lamp.applianceIds)
        assertEquals(listOf("turnOn", "setBrightness"), lamp.actions)
        assertEquals(listOf("Alexa.PowerController", "Alexa.BrightnessController"), lamp.properties.map { it.namespace })
        assertEquals(listOf("heat", "cool"), list.first { it.type == "THERMOSTAT" }.modes)
        assertEquals("August", list.first { it.type == "SMARTLOCK" }.manufacturer)
    }

    @Test fun `the Alexa app and Echo speakers aren't devices of the owner's, and nonsense is ignored`() {
        val list = AlexaParse.parseEndpoints(FakeAmazon.items(
            endpoint("e1", "Alexa app", "OTHER", description = "Alexa Mobile App"),
            endpoint("e2", "Kitchen Echo", "ALEXA_VOICE_ENABLED"),
            endpoint("e3", "Plug", "SMARTPLUG"),
        ))
        assertEquals(listOf("Plug"), list.map { it.name })
        assertTrue(AlexaParse.parseEndpoints(null).isEmpty())
        assertTrue(AlexaParse.parseEndpoints(JsonPrimitive("nope")).isEmpty())
        assertTrue(AlexaParse.parseEndpoints(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("x")))).isEmpty())
    }

    @Test fun `states - power, brightness, a fan, a lock and a contact sensor`() {
        val answer = answer(
            "a1" to listOf(cap("Alexa.PowerController", "powerState", "ON"), cap("Alexa.BrightnessController", "brightness", 40)),
            "a2" to listOf(cap("Alexa.PercentageController", "percentage", 66)),
            "a3" to listOf(cap("Alexa.LockController", "lockState", "LOCKED")),
            "a4" to listOf(cap("Alexa.ContactSensor", "detectionState", "NOT_DETECTED")),
        )
        val r = AlexaParse.parseStates(answer)
        assertEquals(true, r["a1"]!!.state!!.on); assertEquals(40, r["a1"]!!.state!!.brightness)
        assertEquals(66, r["a2"]!!.state!!.percentage)
        assertEquals(LockState.Locked, r["a3"]!!.state!!.lock)
        assertEquals(DoorState.Open, r["a4"]!!.state!!.door)
    }

    @Test fun `temperatures are converted to the account's scale`() {
        val answer = answer(
            "s1" to listOf(cap("Alexa.TemperatureSensor", "temperature", temp(18, "CELSIUS"))),
            "t1" to listOf(cap("Alexa.ThermostatController", "targetSetpoint", temp(70, "FAHRENHEIT")), cap("Alexa.ThermostatController", "thermostatMode", "HEAT")),
        )
        assertEquals(64.4, AlexaParse.parseStates(answer, TempScale.Fahrenheit)["s1"]!!.state!!.temperature!!, 0.001)
        assertEquals(18.0, AlexaParse.parseStates(answer, TempScale.Celsius)["s1"]!!.state!!.temperature!!, 0.001)
        val t = AlexaParse.parseStates(answer, TempScale.Fahrenheit)["t1"]!!.state!!
        assertEquals(70.0, t.setpoint!!, 0.001)
        assertEquals("heat", t.mode)
        val inC = AlexaParse.parseStates(answer, TempScale.Celsius)["t1"]!!.state!!
        assertEquals(21.1, inC.setpoint!!, 0.05)
    }

    @Test fun `unreachable devices, and errors, are told apart`() {
        val body = buildJsonObject {
            put("deviceStates", buildJsonArray {
                add(buildJsonObject {
                    put("entity", buildJsonObject { put("entityId", "a1") })
                    put("capabilityStates", buildJsonArray { add(JsonPrimitive(cap("Alexa.EndpointHealth", "connectivity", buildJsonObject { put("value", "UNREACHABLE") }).toString())) })
                })
            })
            put("errors", buildJsonArray {
                add(buildJsonObject { put("code", "ENDPOINT_UNREACHABLE"); put("entity", buildJsonObject { put("entityId", "a2") }) })
                add(buildJsonObject { put("code", "TargetApplianceNotFoundException"); put("entity", buildJsonObject { put("entityId", "a3") }) })
                add(buildJsonObject { put("code", "SOMETHING_ELSE"); put("entity", buildJsonObject { put("entityId", "a4") }) })
            })
        }
        val r = AlexaParse.parseStates(body)
        assertTrue(r["a1"]!!.unreachable)
        assertTrue(r["a2"]!!.unreachable)
        assertFalse(r["a3"]!!.unreachable); assertNull(r["a3"]!!.state)
        assertFalse(r["a4"]!!.unreachable); assertNull(r["a4"]!!.state)
    }

    @Test fun `a refusal of the whole request, and an answer that isn't one`() {
        assertEquals("Slow down", AlexaParse.stateRefusal(buildJsonObject { put("message", "Slow down") }))
        assertNull(AlexaParse.stateRefusal(answer("a1" to listOf(cap("Alexa.PowerController", "powerState", "ON")))))
        assertTrue(AlexaParse.parseStates(JsonPrimitive("x")).isEmpty())
    }

    @Test fun `a reading is the first of a device's ids that reports one`() {
        val d = AlexaParse.parseEndpoints(FakeAmazon.items(endpoint("e1", "Lamp", "LIGHT", applianceId = "a1", merged = listOf("a1b"), retrievable = listOf(power)))).single()
        val readings = AlexaParse.parseStates(answer("a1b" to listOf(cap("Alexa.PowerController", "powerState", "OFF"))))
        assertEquals(false, AlexaParse.readingOf(d, readings).state!!.on)
        val under = AlexaParse.parseStates(answer("e1" to listOf(cap("Alexa.PowerController", "powerState", "ON"))))
        assertEquals("answers can come back under the entity id", true, AlexaParse.readingOf(d, under).state!!.on)
        assertTrue(AlexaParse.readingOf(d, mapOf("a1" to AlexaReading(null, true))).unreachable)
        assertNull(AlexaParse.readingOf(d, emptyMap()).state)
    }

    @Test fun `state requests ask by appliance id with the readable properties, and skip devices with none`() {
        val list = AlexaParse.parseEndpoints(FakeAmazon.items(
            endpoint("e1", "Lamp", "LIGHT", applianceId = "a1", merged = listOf("a1b"), retrievable = listOf(power)),
            endpoint("e2", "Camera", "CAMERA"),
        ))
        val requests = AlexaParse.stateRequests(list)
        assertEquals(2, requests.size)   // the lamp's two appliance ids
        assertTrue(requests.toString().contains("Alexa.PowerController"))
        assertFalse(requests.toString().contains("Camera"))
        assertFalse("cameras aren't asked (that's what times out)", AlexaParse.shouldAskState(list.first { it.type == "CAMERA" }))
        assertTrue(AlexaParse.shouldAskState(list.first { it.type == "LIGHT" }))
    }

    @Test fun `batches of five keep a maker together where they can, and share the leftovers`() {
        val items = (1..7).map { endpoint("a$it", "A$it", "LIGHT", maker = "Acme", retrievable = listOf(power)) } +
            (1..3).map { endpoint("b$it", "B$it", "LIGHT", maker = "Brand", retrievable = listOf(power)) } +
            (1..2).map { endpoint("c$it", "C$it", "LIGHT", maker = "Cyan", retrievable = listOf(power)) }
        val batches = AlexaParse.stateBatches(AlexaParse.parseEndpoints(JsonArray(items)), 5)
        assertEquals(12, batches.sumOf { it.size })
        assertTrue(batches.all { it.size <= 5 })
        assertEquals("a full batch of one maker", 5, batches.first().size)
        assertEquals(3, batches.size)
    }

    @Test fun `Amazon sites - only amazon dot com reads in Fahrenheit`() {
        assertEquals(TempScale.Fahrenheit, AmazonSite.DEFAULT.scale)
        assertEquals(TempScale.Celsius, AmazonSite.of("amazon.co.uk")!!.scale)
        assertNull(AmazonSite.of("amazon.example"))
    }

    private fun answer(vararg devices: Pair<String, List<kotlinx.serialization.json.JsonObject>>) = buildJsonObject {
        put("deviceStates", buildJsonArray {
            for ((id, caps) in devices) add(buildJsonObject {
                put("entity", buildJsonObject { put("entityId", id) })
                put("capabilityStates", JsonArray(caps.map { JsonPrimitive(it.toString()) }))
            })
        })
    }
}

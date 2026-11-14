package com.edith.app.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** C25 §4: actions to commands, what a device can do, the card rules (C20 §2, D10) and the words for states. */
class HomeActionsTest {
    private val thermostat = HomeDevice("t", "Thermostat", "Hallway", DeviceType.Thermostat,
        setOf(Capability.Thermostat(listOf("heat", "cool", "auto", "off"), TempUnit.Fahrenheit)), "demo",
        state = DeviceState(temperature = 68.0, setpoint = 69.0, mode = "heat"))
    private val celsius = thermostat.copy(capabilities = setOf(Capability.Thermostat(listOf("heat"), TempUnit.Celsius)), state = DeviceState(setpoint = 20.0))
    private val lamp = HomeDevice("l", "Lamp", null, DeviceType.Light,
        setOf(Capability.OnOff, Capability.Brightness, Capability.Color(), Capability.WhiteTone()), "demo", state = DeviceState(on = true, brightness = 60))
    private val lock = HomeDevice("k", "Front door", null, DeviceType.Lock, setOf(Capability.LockUnlock), "demo", state = DeviceState(lock = LockState.Locked))
    private val garage = HomeDevice("g", "Garage door", null, DeviceType.GarageDoor, setOf(Capability.OpenClose), "demo")

    private fun ok(d: HomeDevice, action: String, value: String? = null) = HomeActions.parse(d, action, value).getOrThrow()
    private fun error(d: HomeDevice, action: String, value: String? = null) = HomeActions.parse(d, action, value).exceptionOrNull()!!.message!!

    @Test fun `actions and values become commands`() {
        assertEquals(DeviceCommand.SetBrightness(40), ok(lamp, "set_brightness", "40%"))
        assertEquals(DeviceCommand.SetColor("blue"), ok(lamp, "set_color", "Blue"))
        assertEquals(DeviceCommand.SetWhite("warm white"), ok(lamp, "set_white", "warm"))
        assertEquals(DeviceCommand.SetTemperature(72.0), ok(thermostat, "set_temperature", "72°F"))
        assertEquals(DeviceCommand.SetMode("cool"), ok(thermostat, "set_thermostat_mode", "COOL"))
        assertEquals(DeviceCommand.Unlock, ok(lock, "unlock"))
    }

    @Test fun `what a device can't do is refused with what it can`() {
        assertTrue(error(lock, "turn_on"), "can't turn on" in error(lock, "turn_on") && "lock, unlock" in error(lock, "turn_on"))
        assertTrue("Unknown action" in error(lamp, "dance"))
        assertTrue("percentage" in error(lamp, "set_brightness", "150"))
        assertTrue("no colour \"teal\"" in error(lamp, "set_color", "teal"))
        assertTrue("no mode" in error(thermostat, "set_thermostat_mode", "dry"))
    }

    @Test fun `setpoints are kept within 50-90 F and 10-32 C`() {
        assertTrue("50–90°F" in error(thermostat, "set_temperature", "95"))
        assertTrue("10–32°C" in error(celsius, "set_temperature", "35"))
        assertEquals(DeviceCommand.SetTemperature(21.5), ok(celsius, "set_temperature", "21.5"))
    }

    @Test fun `the card rules`() {
        assertEquals(Approval.None, HomeActions.approval(lamp, DeviceCommand.TurnOff))
        assertEquals(Approval.None, HomeActions.approval(lamp, DeviceCommand.SetColor("red")))
        assertEquals("making things safer needs no card", Approval.None, HomeActions.approval(lock, DeviceCommand.Lock))
        assertEquals(Approval.ScreenOnly, HomeActions.approval(lock, DeviceCommand.Unlock))
        assertEquals(Approval.ScreenOnly, HomeActions.approval(garage, DeviceCommand.Open))
        assertEquals(Approval.Card, HomeActions.approval(garage, DeviceCommand.Close))
        assertEquals(Approval.Card, HomeActions.approval(thermostat, DeviceCommand.SetMode("cool")))
        assertEquals("69 → 74: 5 °F, not more", Approval.None, HomeActions.approval(thermostat, DeviceCommand.SetTemperature(74.0)))
        assertEquals("69 → 75: more than 5 °F", Approval.Card, HomeActions.approval(thermostat, DeviceCommand.SetTemperature(75.0)))
        assertEquals("20 → 23.5 °C: more than 3", Approval.Card, HomeActions.approval(celsius, DeviceCommand.SetTemperature(23.5)))
        assertEquals("unknown setpoint: ask", Approval.Card,
            HomeActions.approval(thermostat.copy(state = null), DeviceCommand.SetTemperature(70.0)))
    }

    @Test fun `states in words`() {
        assertEquals("On, 60%", HomeActions.stateText(lamp))
        assertEquals("Heat to 69°F, 68°F now", HomeActions.stateText(thermostat))
        assertEquals("Off, 68°F now", HomeActions.stateText(thermostat, DeviceState(mode = "off", temperature = 68.0)))
        assertEquals("Locked, battery 15%", HomeActions.stateText(lock, DeviceState(lock = LockState.Locked, battery = 15)))
        assertEquals("a good battery isn't mentioned", "Locked", HomeActions.stateText(lock, DeviceState(lock = LockState.Locked, battery = 80)))
        assertEquals("", HomeActions.stateText(garage))
        assertEquals("3 min ago", HomeActions.age(0, 3 * 60_000 + 5_000))
        assertEquals("just now", HomeActions.age(0, 30_000))
    }

    @Test fun `when a door last moved`() {
        val now = 1_800_000_000_000L
        assertEquals("moved 3 h ago", HomeActions.movedText(DeviceState(door = DoorState.Open, changedAt = now - 3 * 3_600_000L - 5_000), now))
        assertEquals("moved 5 min ago", HomeActions.movedText(DeviceState(changedAt = now - 5 * 60_000L), now))
        assertEquals("unknown or silly times say nothing", listOf(null, null, null),
            listOf(HomeActions.movedText(null, now), HomeActions.movedText(DeviceState(), now), HomeActions.movedText(DeviceState(changedAt = now + 3_600_000L), now)))
    }
}

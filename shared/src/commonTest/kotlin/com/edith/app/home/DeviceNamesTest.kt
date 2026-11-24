package com.edith.app.home

import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertTrue
import kotlin.test.Test

/** C25 §3: exact name, room and name, contains, a copied "(Light)", ambiguous; nothing fuzzier. */
class DeviceNamesTest {
    private fun d(name: String, room: String?, type: DeviceType = DeviceType.Light) =
        HomeDevice(name.lowercase().replace(' ', '-') + (room ?: ""), name, room, type, setOf(Capability.OnOff), "demo")

    private val devices = listOf(
        d("Porch light", "Outside"),
        d("Lamp", "Living room"),
        d("Lamp", "Bedroom"),
        d("Front door", "Entry", DeviceType.Lock),
        d("Front door camera", "Entry", DeviceType.Doorbell),
        d("Ceiling fan", "Bedroom", DeviceType.Fan),
    )

    private fun found(name: String) = (DeviceNames.match(devices, name) as DeviceMatch.Found).device

    @Test fun `exact names win in any case with or without the`() {
        assertEquals("Porch light", found("porch LIGHT").name)
        assertEquals("Porch light", found("the porch light").name)
        assertEquals("exact beats contains", DeviceType.Lock, found("Front door").type)
    }

    @Test fun `room and name either way round`() {
        assertEquals("Living room", found("living room lamp").room)
        assertEquals("Bedroom", found("lamp in the bedroom").room)
    }

    @Test fun `the only device containing the words`() {
        assertEquals("Porch light", found("porch").name)
        assertEquals("Ceiling fan", found("bedroom fan").name)
        assertEquals("Front door camera", found("camera").name)
    }

    @Test fun `a category the model copied from the list is ignored`() {
        assertEquals("Porch light", found("Porch light (Light)").name)
    }

    @Test fun `an unclear name lists the candidates`() {
        val m = DeviceNames.match(devices, "lamp") as DeviceMatch.Ambiguous
        assertEquals(2, m.candidates.size)
        val text = DeviceNames.problem("lamp", m)
        assertTrue(text, "\"Lamp\" (Living room)" in text && "\"Lamp\" (Bedroom)" in text && "Ask the owner" in text)
    }

    @Test fun `no match and no fuzzy guessing`() {
        assertEquals(DeviceMatch.NotFound, DeviceNames.match(devices, "porch lite"))
        assertEquals(DeviceMatch.NotFound, DeviceNames.match(devices, "garage"))
        assertTrue("home_devices" in DeviceNames.problem("garage", DeviceMatch.NotFound))
    }
}

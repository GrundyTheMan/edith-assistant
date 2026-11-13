package com.edith.app.home

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.settings.Settings
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolResult
import com.edith.app.voice.handsfree.HandsFree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C25 §4: the three home tools against the Demo home, their cards, and hands-free's words for a card. */
class HomeToolsTest {
    private fun args(tool: com.edith.app.tools.framework.Tool, vararg pairs: Pair<String, String>) =
        ToolArgs(buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }, tool.spec)

    private fun ctx(k: HomeKit) = ToolContext(k.clock, Settings(), AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined))

    @Test fun `home_devices lists every device by room, and filters`() = runTest {
        val k = HomeKit(this, backgroundScope)
        val tool = HomeDevicesTool(k.home) { SchedulerClock.START }
        val all = tool.execute(args(tool), ctx(k)).text
        assertTrue(all, "Outside:\n- Porch light (Light): Off" in all)
        assertTrue(all, "- Thermostat (Thermostat): Heat to 69°F, 68°F now" in all)
        val locks = tool.execute(args(tool, "filter" to "locks"), ctx(k)).text
        assertEquals(locks, 2, locks.lines().count { it.startsWith("- ") })
        val kitchen = tool.execute(args(tool, "filter" to "kitchen"), ctx(k)).text
        assertTrue(kitchen, "Coffee maker" in kitchen && "Back door" in kitchen && "Porch" !in kitchen)
        assertTrue("No device matches" in tool.execute(args(tool, "filter" to "pool"), ctx(k)).text)
    }

    @Test fun `turning on a light needs no card and is done`() = runTest {
        val k = HomeKit(this, backgroundScope)
        val tool = HomeControlTool(k.home)
        val a = args(tool, "device" to "porch light", "action" to "turn_on")
        assertNull(tool.confirmation(a))
        val r = tool.execute(a, ctx(k))
        assertTrue(r.text, r is ToolResult.Ok && "It now reports: On, 100%" in r.text)
        assertEquals(listOf("porch" to DeviceCommand.TurnOn), k.fake.received)
        assertEquals(CommandSource.Chat, k.log.all.single().source)
    }

    @Test fun `unlocking always shows a screen-only card`() = runTest {
        val k = HomeKit(this, backgroundScope)
        val tool = HomeControlTool(k.home)
        val card = tool.confirmation(args(tool, "device" to "front door", "action" to "unlock"))!!
        assertTrue(card.screenOnly)
        assertEquals("Unlock: Front door?", card.title)
        assertEquals("Unlock", card.confirmLabel)
        assertTrue(card.details.any { it.first == "Note" })
        assertEquals("For safety, this needs a tap on the screen: tap Unlock to go ahead.", HandsFree.confirmPrompt(card))
        val open = tool.confirmation(args(tool, "device" to "garage door", "action" to "open"))!!
        assertTrue(open.screenOnly)
        val close = tool.confirmation(args(tool, "device" to "garage", "action" to "close"))!!
        assertTrue("closing: a card, but voice could approve it", !close.screenOnly)
        assertEquals("Tap Close on the screen to go ahead, or Cancel.", HandsFree.confirmPrompt(close))
        assertNull("locking: none", tool.confirmation(args(tool, "device" to "front door", "action" to "lock")))
    }

    @Test fun `a big thermostat change shows a card, a small one doesn't`() = runTest {
        val k = HomeKit(this, backgroundScope)
        val tool = HomeControlTool(k.home)
        assertNull(tool.confirmation(args(tool, "device" to "thermostat", "action" to "set_temperature", "value" to "72")))
        val card = tool.confirmation(args(tool, "device" to "thermostat", "action" to "set_temperature", "value" to "78"))!!
        assertEquals("Set it to 78°F: Thermostat?", card.title)
        assertTrue(!card.screenOnly)
    }

    @Test fun `unclear and impossible requests are explained, not guessed`() = runTest {
        val k = HomeKit(this, backgroundScope)
        val tool = HomeControlTool(k.home)
        val door = tool.execute(args(tool, "device" to "door", "action" to "lock"), ctx(k))
        assertTrue(door.text, door is ToolResult.Error && "could mean" in door.text && "Front door" in door.text && "Back door" in door.text)
        val fan = tool.execute(args(tool, "device" to "ceiling fan", "action" to "set_color", "value" to "red"), ctx(k))
        assertTrue(fan.text, fan is ToolResult.Error && "can't set the colour" in fan.text)
        assertNull("no card for what can't be done", tool.confirmation(args(tool, "device" to "door", "action" to "unlock")))
        assertTrue(k.fake.received.isEmpty())
    }

    @Test fun `a camera's picture comes back with the result`() = runTest {
        val k = HomeKit(this, backgroundScope)
        val tool = CameraSnapshotTool(k.home)
        val r = tool.execute(args(tool, "device" to "doorbell"), ctx(k)) as ToolResult.Ok
        assertEquals(listOf("Doorbell.jpg"), r.images.map { it.fileName })
        assertEquals(3, r.images.single().jpeg.size)
        val notCamera = tool.execute(args(tool, "device" to "porch light"), ctx(k))
        assertTrue(notCamera is ToolResult.Error && "isn't a camera" in notCamera.text)
    }

    @Test fun `hidden devices are out of Edith's reach`() = runTest {
        val k = HomeKit(this, backgroundScope)
        k.home.refresh()
        k.home.setHidden(k.device("Garage door").key, "Garage door", true)
        val list = HomeDevicesTool(k.home)
        assertTrue("Garage" !in list.execute(args(list), ctx(k)).text)
        val control = HomeControlTool(k.home)
        assertTrue(control.execute(args(control, "device" to "garage door", "action" to "close"), ctx(k)) is ToolResult.Error)
    }
}

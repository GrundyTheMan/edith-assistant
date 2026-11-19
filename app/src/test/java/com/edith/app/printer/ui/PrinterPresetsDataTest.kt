package com.edith.app.printer.ui

import com.edith.app.jarvis.FeedResult
import com.edith.app.printer.MemoryPrinterPrefs
import com.edith.app.printer.feed.Connections
import com.edith.app.printer.feed.FakePrinterFeed
import com.edith.app.printer.feed.FakePrinterRoutes
import com.edith.app.printer.feed.Preset
import com.edith.app.printer.feed.PrinterAccess
import com.edith.app.printer.feed.PrinterSession
import com.edith.app.printer.feed.PrinterState
import com.edith.app.printer.feed.PrinterWords
import com.edith.app.printer.feed.SettingsAnswer
import com.edith.app.printer.feed.SharedPrinter
import com.edith.app.printer.feed.StateAnswer
import com.edith.app.testing.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** C39 E10b: saving the temperature presets through Jarvis. */
@OptIn(ExperimentalCoroutinesApi::class)
class PrinterPresetsDataTest {
    private val clock = FakeClock()
    private class Rig(val routes: FakePrinterRoutes, val session: PrinterSession, val data: PrinterCardData)

    private fun TestScope.rig(): Rig {
        val routes = FakePrinterRoutes()
        val session = PrinterSession(
            FakePrinterFeed(PrinterAccess.Ready(routes, SharedPrinter(name = "AD5X", control = true, presets = listOf(Preset("PLA", 220, 60))))), clock, backgroundScope as CoroutineScope,
        )
        val data = PrinterCardData(session, MemoryPrinterPrefs(), backgroundScope as CoroutineScope)
        session.attach(); runCurrent()
        routes.say(StateAnswer(rev = 1, epoch = 1, state = PrinterState(connection = Connections.READY)))
        runCurrent()
        return Rig(routes, session, data)
    }

    private val mine = listOf(Preset("PLA", 215, 60), Preset("ASA", 260, 100))

    @Test fun `saving sends the presets to Jarvis and the page shows what Jarvis kept`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.routes.settingsAnswer = FeedResult.Ok(SettingsAnswer(presets = mine))
        r.data.savePresets(mine); runCurrent()
        assertEquals(listOf<Pair<List<Preset>?, List<String>?>>(mine to null), r.routes.settingsCalls)
        assertEquals(mine, r.session.shared.value?.presets)
        assertEquals(1, r.data.ui.value.presets.saved)
        assertFalse(r.data.ui.value.presets.saving)
        assertNull(r.data.ui.value.presets.error)
        r.data.detached()
    }

    @Test fun `what Jarvis answers is what is shown, not what was sent`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.routes.settingsAnswer = FeedResult.Ok(SettingsAnswer(presets = listOf(Preset("PLA", 220, 60))))   // Jarvis rounded or changed it
        r.data.savePresets(mine); runCurrent()
        assertEquals(listOf(Preset("PLA", 220, 60)), r.session.shared.value?.presets)
        r.data.detached()
    }

    @Test fun `a refusal shows Jarvis's words, keeps the old presets and does not close the editor`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.routes.settingsAnswer = FeedResult.Err(409, "CONTROLS_OFF", "x", "")
        r.data.savePresets(mine); runCurrent()
        assertEquals(PrinterWords.CONTROLS_OFF, r.data.ui.value.presets.error)
        assertEquals(0, r.data.ui.value.presets.saved)
        assertEquals(listOf(Preset("PLA", 220, 60)), r.session.shared.value?.presets)
        r.data.clearPresetsError()
        assertNull(r.data.ui.value.presets.error)
        r.data.detached()
    }

    @Test fun `Jarvis not reachable is told in plain words`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.routes.settingsAnswer = FeedResult.Unreachable
        r.data.savePresets(mine); runCurrent()
        assertEquals(PrinterWords.UNREACHABLE, r.data.ui.value.presets.error)
        r.data.detached()
    }

    @Test fun `saving none sends an empty list, which Jarvis takes as its defaults`() = runTest(UnconfinedTestDispatcher()) {
        val r = rig()
        r.routes.settingsAnswer = FeedResult.Ok(SettingsAnswer(presets = listOf(Preset("PLA", 220, 60), Preset("PETG", 240, 80), Preset("ABS", 260, 100))))
        r.data.savePresets(emptyList()); runCurrent()
        assertEquals(listOf<Pair<List<Preset>?, List<String>?>>(emptyList<Preset>() to null), r.routes.settingsCalls)
        assertEquals(3, r.session.shared.value?.presets?.size)
        r.data.detached()
    }
}

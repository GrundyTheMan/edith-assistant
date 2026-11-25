package com.edith.app.home.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.edith.app.home.Capability
import com.edith.app.home.DeviceCommand
import com.edith.app.home.DeviceState
import com.edith.app.home.DeviceType
import com.edith.app.home.HomeDevice
import com.edith.app.home.HomeView
import com.edith.app.home.LockState
import com.edith.app.ui.theme.EdithTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.test.Test
import kotlin.test.assertEquals

/** C25 §5, K3.3: the Home tab (shared since K3.3, so it runs on the desktop and the iPhone simulator): not connected, chips, a one-tap toggle, the lock section and the unlock dialog. */
@OptIn(ExperimentalTestApi::class)
class HomeContentTest {

    private val now = 1_800_000_000_000L
    private val porch = HomeDevice("porch", "Porch light", "Outside", DeviceType.Light, setOf(Capability.OnOff, Capability.Brightness), "demo",
        state = DeviceState(on = false, brightness = 100), stateAt = now - 3 * 60_000)
    private val door = HomeDevice("door", "Front door", "Entry", DeviceType.Lock, setOf(Capability.LockUnlock), "demo",
        state = DeviceState(lock = LockState.Locked), stateAt = now)
    private val view = HomeView(devices = listOf(porch, door), readAt = now)

    @Test fun notConnectedPointsToSettings() = runComposeUiTest {
        var opened = false
        setContent { EdithTheme { HomeContent(HomeUi(connected = false), HomeTabActions(openSettings = { opened = true })) } }
        onNodeWithText("Settings → Home").performClick()
        assertEquals(true, opened)
    }

    @Test fun chipsTogglesAndTheUnlockDialog() = runComposeUiTest {
        val sent = mutableListOf<Pair<String, DeviceCommand>>()
        var ui by mutableStateOf(HomeUi(connected = true, view = view, now = now))
        setContent {
            EdithTheme {
                HomeContent(ui, HomeTabActions(
                    chip = { ui = ui.copy(chip = it) },
                    toggle = { sent += it.name to DeviceCommand.TurnOn },
                    // As the ViewModel does: unlock waits for the dialog.
                    send = { d, c -> if (c == DeviceCommand.Unlock) ui = ui.copy(pending = PendingCommand(d, c, screenOnly = true)) else sent += d.name to c },
                    confirm = { ui.pending?.let { sent += it.device.name to it.command }; ui = ui.copy(pending = null) },
                    dismiss = { ui = ui.copy(pending = null) },
                ))
            }
        }
        onNodeWithText("Off · 3 min ago").assertIsDisplayed()
        onAllNodesWithText("Locks and doors", useUnmergedTree = true).assertCountEquals(2)   // the chip, and the section it opens

        onNodeWithContentDescription("Porch light on or off").performClick()
        assertEquals<List<Pair<String, DeviceCommand>>>(listOf("Porch light" to DeviceCommand.TurnOn), sent)

        onNodeWithText("Unlock").performClick()
        onNodeWithText("Unlock: Front door?").assertIsDisplayed()
        assertEquals(1, sent.size, "nothing is sent before the tap")
        onNodeWithTag(CONFIRM_TAG).performClick()
        assertEquals("Front door" to DeviceCommand.Unlock, sent.last())

        onNodeWithText("Lights").performClick()
        onNodeWithText("Porch light").assertIsDisplayed()
        onNodeWithText("Front door").assertDoesNotExist()
    }
}

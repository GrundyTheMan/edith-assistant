package com.edith.app.home.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** C25 §5: the Home tab: not connected, chips, a one-tap toggle, the lock section and the unlock dialog. */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val porch = HomeDevice("porch", "Porch light", "Outside", DeviceType.Light, setOf(Capability.OnOff, Capability.Brightness), "demo",
        state = DeviceState(on = false, brightness = 100), stateAt = now - 3 * 60_000)
    private val door = HomeDevice("door", "Front door", "Entry", DeviceType.Lock, setOf(Capability.LockUnlock), "demo",
        state = DeviceState(lock = LockState.Locked), stateAt = now)
    private val view = HomeView(devices = listOf(porch, door), readAt = now)

    @Test fun notConnectedPointsToSettings() {
        var opened = false
        compose.setContent { EdithTheme { HomeContent(HomeUi(connected = false), HomeTabActions(openSettings = { opened = true })) } }
        compose.onNodeWithText("Settings → Home").performClick()
        assertEquals(true, opened)
    }

    @Test fun chipsTogglesAndTheUnlockDialog() {
        val sent = mutableListOf<Pair<String, Any>>()
        var ui by mutableStateOf(HomeUi(connected = true, view = view, now = now))
        compose.setContent {
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
        compose.onNodeWithText("Off · 3 min ago").assertIsDisplayed()
        compose.onNodeWithText("Locks and doors", useUnmergedTree = true).assertExists()

        compose.onNodeWithContentDescription("Porch light on or off").performClick()
        assertEquals(listOf("Porch light" to DeviceCommand.TurnOn), sent)

        compose.onNodeWithText("Unlock").performClick()
        compose.onNodeWithText("Unlock: Front door?").assertIsDisplayed()
        assertEquals("nothing is sent before the tap", 1, sent.size)
        compose.onNodeWithTag(CONFIRM_TAG).performClick()
        assertEquals("Front door" to DeviceCommand.Unlock, sent.last())

        compose.onNodeWithText("Lights").performClick()
        compose.onNodeWithText("Porch light").assertIsDisplayed()
        compose.onNodeWithText("Front door").assertDoesNotExist()
    }
}

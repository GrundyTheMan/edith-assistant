package com.edith.app.printer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.edith.app.printer.feed.PrinterFormat
import kotlinx.coroutines.delay

/** What a control that sends a command must say when it cannot be pressed: Controls off, Jarvis away, or the server's own reason. */
internal const val TURN_ON_CONTROLS = "Turn on Controls to use this"

/**
 * Why a control is disabled, or null when it can be used. [quickKey] is the key of the `quick` map for the parameterless commands
 * (`pause`, `home:xyz`, ...): the server's reason is shown for those. Everything with a parameter is open when Controls are on and the
 * server's refusal is shown inline when it says no.
 */
internal fun disabledReason(ui: PrinterUi, c: ControlUi, quickKey: String? = null): String? = when {
    ui.greyed -> "Jarvis isn't reachable right now."
    !c.armed -> TURN_ON_CONTROLS
    quickKey != null && ui.quick[quickKey]?.allowed == false -> ui.quick[quickKey]?.reason ?: "Not now."
    else -> null
}

/**
 * The Controls switch (C39 part 7.1). Off when the page opens. On: "Controls on · 1:43", counting down from Jarvis's own timer (the phone's
 * clock may be wrong, so the countdown uses Jarvis's clock). Look-only phones get one line instead.
 */
@Composable
internal fun ControlsBar(ui: PrinterUi, c: ControlUi, onToggle: (Boolean) -> Unit) {
    if (!ui.showsCards) return
    if (!c.mayControl) {
        Text(
            "Jarvis lets this phone look, not control.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("controls-look-only"),
        )
        return
    }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(c.armed) { while (c.armed) { nowMs = System.currentTimeMillis(); delay(1_000) } }
    Surface(color = if (c.armed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().testTag("controls-bar")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (c.armed) "Controls on" + (c.armUntilMs?.let { " · " + PrinterFormat.countdown(it, nowMs, c.offsetMs) } ?: "") else "Controls",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).testTag("controls-text"),
            )
            Switch(checked = c.armed, onCheckedChange = onToggle, enabled = !ui.greyed, modifier = Modifier.testTag("controls-switch"))
        }
    }
}

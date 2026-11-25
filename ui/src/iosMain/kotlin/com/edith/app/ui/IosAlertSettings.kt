package com.edith.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.edith.app.alerts.AlertCatalog
import com.edith.app.alerts.AlertChoices
import com.edith.app.alerts.AlertPrefs
import com.edith.app.home.Home
import com.edith.app.home.HomeService
import com.edith.app.home.ServiceState
import kotlinx.coroutines.launch

/**
 * Settings → Camera alerts on the iPhone (K4.16): for each connected service that can alert (Ring: doorbell, person, motion; Blink: motion), a switch per kind,
 * all off until switched on. iOS runs the app only while it is open, so these are banners in the app and the "While you were away" list on the Home tab, not
 * notifications while it is closed.
 */
@Composable
fun IosAlertSettings(home: Home, prefs: AlertPrefs) {
    val services by home.services.collectAsState()
    val choices by prefs.choices.collectAsState(initial = AlertChoices())
    val scope = rememberCoroutineScope()
    val alerting = services.filter { it.backend.id in AlertCatalog.services }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Camera alerts", style = MaterialTheme.typography.titleMedium)
        Text(
            "Edith checks your cameras every 45 seconds while she is open and shows what she finds here; what happens while she is closed is listed under While you were away on the Home tab.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (alerting.none { it.state.collectAsState().value == ServiceState.Connected }) Text("Connect Ring or Blink in the smart-home section first.")
        alerting.forEach { service -> ServiceAlerts(service, choices, prefs) { block -> scope.launch { block() } } }
    }
}

@Composable
private fun ServiceAlerts(service: HomeService, choices: AlertChoices, prefs: AlertPrefs, launch: (suspend () -> Unit) -> Unit) {
    val state by service.state.collectAsState()
    if (state != ServiceState.Connected) return
    val id = service.backend.id
    Text(service.backend.displayName, style = MaterialTheme.typography.bodyLarge)
    AlertCatalog.kindsOf(id).forEach { kind ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(kind.label, style = MaterialTheme.typography.bodyMedium)
            Switch(checked = choices.enabled(id, kind), onCheckedChange = { on -> launch { prefs.set(id, kind, on) } })
        }
    }
}

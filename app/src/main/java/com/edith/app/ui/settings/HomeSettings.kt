package com.edith.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edith.app.di.viewModelFactory
import com.edith.app.home.CommandSource
import com.edith.app.home.Home
import com.edith.app.home.LoggedCommand
import com.edith.app.tools.framework.IntegrationStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One service in Settings → Home: its name and whether it's connected. Sign-in buttons come with C26–C31. */
data class HomeServiceRow(val id: String, val name: String, val connected: Boolean)

data class HomeSettingsUi(
    val services: List<HomeServiceRow> = emptyList(),
    /** Debug builds: the Demo home switch exists. */
    val hasDemo: Boolean = false,
    val demo: Boolean = false,
    /** Hidden devices: (key, name). */
    val hidden: List<Pair<String, String>> = emptyList(),
    val log: List<LoggedCommand> = emptyList(),
)

/** Settings → Home (C25 §7). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeSettingsViewModel(private val home: Home, log: kotlinx.coroutines.flow.Flow<List<LoggedCommand>>) : ViewModel() {
    private val services = home.backends.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyList())
        else combine(list.map { b -> b.status }) { states ->
            list.mapIndexed { i, b -> HomeServiceRow(b.id, b.displayName, states[i] == IntegrationStatus.Connected) }
        }
    }

    val state: StateFlow<HomeSettingsUi> = combine(services, home.choices, log) { s, c, l ->
        HomeSettingsUi(
            services = s.filterNot { it.id == com.edith.app.home.FakeHomeBackend.ID },
            hasDemo = home.hasDemo, demo = c.demo,
            hidden = c.hidden.map { it to (c.hiddenNames[it] ?: it.substringAfter(':')) }.sortedBy { it.second.lowercase() },
            log = l,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeSettingsUi(hasDemo = home.hasDemo))

    fun setDemo(on: Boolean) {
        viewModelScope.launch { home.setDemo(on) }
    }

    fun show(key: String, name: String) {
        viewModelScope.launch { home.setHidden(key, name, false) }
    }

    companion object {
        val Factory = viewModelFactory { c, _ -> HomeSettingsViewModel(c.home, c.homeCommandLog.recent) }
    }
}

@Composable
fun HomeSection(vm: HomeSettingsViewModel = viewModel(factory = HomeSettingsViewModel.Factory)) {
    val ui by vm.state.collectAsStateWithLifecycle()
    HomeSettingsContent(ui, vm::setDemo, vm::show)
}

@Composable
fun HomeSettingsContent(ui: HomeSettingsUi, onDemo: (Boolean) -> Unit, onShow: (String, String) -> Unit) {
    var showLog by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Home", style = MaterialTheme.typography.titleMedium)
        Text(
            "Edith controls your lights, locks, thermostat and garage, and shows your cameras, from this phone. " +
                "Unlocking a door and opening the garage always need a tap on the screen.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (ui.services.isEmpty()) {
            Text("No smart-home service is connected yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ui.services.forEach { s ->
            Text("${s.name}: ${if (s.connected) "Connected" else "Not connected"}", style = MaterialTheme.typography.bodyMedium)
        }
        if (ui.hasDemo) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Demo home", style = MaterialTheme.typography.bodyLarge)
                    Text("Debug builds only: a made-up house to try the Home tab and Edith's home tools.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = ui.demo, onCheckedChange = onDemo, modifier = Modifier.testTag(DEMO_TAG))
            }
        }
        if (ui.hidden.isNotEmpty()) {
            Text("Hidden devices", style = MaterialTheme.typography.labelLarge)
            ui.hidden.forEach { (key, name) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(name, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onShow(key, name) }) { Text("Show again") }
                }
            }
        }
        TextButton(onClick = { showLog = true }) { Text("Command log (${ui.log.size})") }
    }
    if (showLog) CommandLogDialog(ui.log) { showLog = false }
}

private val logTime = DateTimeFormatter.ofPattern("MMM d, h:mm:ss a", Locale.US)

/** The owner's audit view (C20 §5): every command Edith sent, newest first. */
@Composable
private fun CommandLogDialog(log: List<LoggedCommand>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Command log") },
        text = {
            if (log.isEmpty()) Text("No commands yet.")
            else LazyColumn(Modifier.heightIn(max = 480.dp).testTag(LOG_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(log, key = { it.id }) { e ->
                    Column {
                        Text("${e.deviceName}: ${e.action.replace('_', ' ')}${e.value?.let { " $it" } ?: ""}", fontWeight = FontWeight.Medium)
                        Text(
                            "${logTime.format(Instant.ofEpochMilli(e.at).atZone(ZoneId.systemDefault()))} · " +
                                (if (e.source == CommandSource.HomeTab) "Home tab" else "Chat") + " · " +
                                (if (e.ok) e.result else "Failed: ${e.result}"),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (e.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

const val DEMO_TAG = "home-demo"
const val LOG_TAG = "home-log"

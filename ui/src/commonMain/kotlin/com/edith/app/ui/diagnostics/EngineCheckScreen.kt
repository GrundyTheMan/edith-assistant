package com.edith.app.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.edith.app.diagnostics.EngineCheck
import com.edith.app.ui.gallery.ComponentGalleryContent
import kotlinx.coroutines.launch

/**
 * The first screen of the iPhone app (K4.1): the shared engine's self-test, then the shared components. It exists so the first real-phone
 * build proves what no simulator can: typing an address into Compose, the Keychain, the database file and Ollama over the owner's network.
 * It is replaced by the chat screen as K3.2 and K4.2 land.
 */
@Composable
fun EngineCheckScreen(check: EngineCheck) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<EngineCheck.Line>>(emptyList()) }
    LaunchedEffect(Unit) { address = check.savedServerUrl() }

    // safeDrawing: stays clear of the notch, the home indicator and the keyboard (the first simulator picture showed the title under the clock).
    Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Edith engine check", style = MaterialTheme.typography.headlineSmall)
            Text("The shared engine, running on this phone.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Ollama address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    running = true
                    scope.launch {
                        lines = check.run(address)
                        running = false
                    }
                },
                enabled = !running && address.isNotBlank(),
            ) { Text(if (running) "Checking…" else "Run the checks") }
            lines.forEach { line ->
                Row(Modifier.semantics { contentDescription = (if (line.ok) "Passed: " else "Failed: ") + line.name + ", " + line.detail }) {
                    Text(if (line.ok) "✓ " else "✗ ", color = if (line.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Text("${line.name}: ${line.detail}")
                }
            }
        }
        ComponentGalleryContent()
    }
}

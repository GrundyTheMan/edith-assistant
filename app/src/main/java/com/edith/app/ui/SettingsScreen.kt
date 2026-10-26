package com.edith.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.edith.app.utils.ConfigManager

@Composable
fun SettingsScreen(
    configManager: ConfigManager,
    signedInEmail: String?,
    onSignIn: () -> Unit
) {
    var url by remember { mutableStateOf(configManager.getOllamaUrl()) }
    var model by remember { mutableStateOf(configManager.getModelName()) }
    var speakReplies by remember { mutableStateOf(configManager.isSpeakRepliesEnabled()) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        TextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server URL") },
            supportingText = { Text("Edith's voice server (port 11435), or Ollama directly (port 11434) without her voice") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        TextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model Name") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                configManager.setOllamaUrl(url)
                configManager.setModelName(model)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Settings")
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Speak Edith's replies", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Uses the voice server's voice when it's running, otherwise the phone's",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = speakReplies,
                onCheckedChange = {
                    speakReplies = it
                    configManager.setSpeakRepliesEnabled(it)
                }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(text = "Google account", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = signedInEmail?.let { "Signed in as $it" } ?: "Not signed in",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
            Text(if (signedInEmail == null) "Sign in with Google" else "Switch account")
        }
    }
}

package com.edith.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        TextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Ollama Server URL") },
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

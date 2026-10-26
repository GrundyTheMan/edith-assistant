package com.edith.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.edith.app.network.ModelDetailsRequest
import com.edith.app.network.NetworkClient
import com.edith.app.utils.ConfigManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

/** A model Edith can chat with, from the Ollama server. */
private data class ChatModel(val name: String, val supportsImages: Boolean, val supportsAudio: Boolean) {
    val abilities: String? get() = when {
        supportsImages && supportsAudio -> "Understands images and audio (voice memos)"
        supportsImages -> "Understands images"
        supportsAudio -> "Understands audio (voice memos)"
        else -> null
    }
}

/** Lists the server's models that can chat, skipping ones that can't (e.g. embedding models). */
private suspend fun loadChatModels(url: String): List<ChatModel> = coroutineScope {
    NetworkClient.clear()
    val api = NetworkClient.getOllamaApi(url)
    api.listModels().models.map { tag ->
        async {
            // If a model's details can't be read, list it rather than hide it
            val capabilities = runCatching { api.showModel(ModelDetailsRequest(tag.name)).capabilities }.getOrNull()
            if (capabilities != null && "completion" !in capabilities) null
            else ChatModel(
                tag.name,
                supportsImages = capabilities?.contains("vision") == true,
                supportsAudio = capabilities?.contains("audio") == true
            )
        }
    }.awaitAll().filterNotNull().sortedBy { it.name }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    configManager: ConfigManager,
    signedInEmail: String?,
    onSignIn: () -> Unit
) {
    var url by remember { mutableStateOf(configManager.getOllamaUrl()) }
    var model by remember { mutableStateOf(configManager.getModelName()) }
    var speakReplies by remember { mutableStateOf(configManager.isSpeakRepliesEnabled()) }

    var models by remember { mutableStateOf<List<ChatModel>?>(null) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var reloadCount by remember { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(url, reloadCount) {
        models = null
        modelsError = null
        delay(600) // wait for the user to stop typing the URL
        try {
            models = loadChatModels(url.trim())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            modelsError = e.message ?: e::class.java.simpleName
        }
    }

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

        ExposedDropdownMenuBox(
            expanded = menuOpen,
            onExpandedChange = { open ->
                // With no list loaded (server unreachable), tapping the field tries again
                if (models == null) reloadCount++ else menuOpen = open
            }
        ) {
            val loadedModels = models
            TextField(
                value = model,
                onValueChange = {},
                readOnly = true,
                label = { Text("Model") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                isError = modelsError != null || (loadedModels != null && loadedModels.none { it.name == model }),
                supportingText = {
                    Text(
                        when {
                            modelsError != null -> "Couldn't load models from the server: $modelsError. Tap to retry."
                            loadedModels == null -> "Loading models from the server…"
                            loadedModels.none { it.name == model } -> "\"$model\" isn't installed on this server"
                            else -> "${loadedModels.size} models available"
                        }
                    )
                },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                loadedModels?.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.name)
                                option.abilities?.let { abilities ->
                                    Text(
                                        text = abilities,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        onClick = {
                            model = option.name
                            menuOpen = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }

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
                    text = "Reads each new reply aloud with the phone's voice",
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

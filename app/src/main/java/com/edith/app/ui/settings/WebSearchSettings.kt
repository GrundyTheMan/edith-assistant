package com.edith.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.di.viewModelFactory
import com.edith.app.tools.web.OllamaWebSearch
import com.edith.app.tools.web.WebSearchSetup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WebSearchUiState(
    val keySaved: Boolean = false,
    /** What's typed; never pre-filled with the saved key, which never leaves SecretStore for display. */
    val keyField: String = "",
    val status: String? = null,
    val statusIsError: Boolean = false,
    val testing: Boolean = false,
)

/** Settings → Web search (C10 §3, D3): the optional Ollama API key, saved encrypted, with a Test that runs a real search. */
class WebSearchSettingsViewModel(private val setup: WebSearchSetup, private val search: OllamaWebSearch) : ViewModel() {
    private val _state = MutableStateFlow(WebSearchUiState())
    val state: StateFlow<WebSearchUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { setup.refresh() }   // read here, not at startup: searches read the key when they run
        viewModelScope.launch {
            setup.keySaved.collect { saved -> _state.update { it.copy(keySaved = saved) } }
        }
    }

    fun onKeyChange(text: String) = _state.update { it.copy(keyField = text, status = null) }

    fun save() {
        val key = _state.value.keyField.trim()
        if (key.isEmpty()) return
        viewModelScope.launch {
            setup.save(key)
            _state.update { it.copy(keyField = "", status = "Key saved. Edith searches with Ollama first now.", statusIsError = false) }
        }
    }

    fun remove() {
        viewModelScope.launch {
            setup.save(null)
            _state.update { it.copy(keyField = "", status = "Key removed. Edith searches with DuckDuckGo.", statusIsError = false) }
        }
    }

    /** Searches "weather" with the typed key (or the saved one) and reports plainly. */
    fun test() {
        viewModelScope.launch {
            _state.update { it.copy(testing = true, status = "Testing…", statusIsError = false) }
            val key = _state.value.keyField.trim().ifEmpty { setup.key() }
            val (message, error) = when (val r = search.search("weather", 1, key)) {
                is Outcome.Success -> "Web search works." to false
                is Outcome.Failure -> ((r.error as? AppError.ToolFailed)?.detail ?: r.error.userMessage) to true
            }
            _state.update { it.copy(testing = false, status = message, statusIsError = error) }
        }
    }

    companion object {
        val Factory = viewModelFactory { container, _ -> WebSearchSettingsViewModel(container.webSearchSetup, container.ollamaWeb) }
    }
}

@Composable
fun WebSearchSection(vm: WebSearchSettingsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    WebSearchContent(state, vm::onKeyChange, vm::save, vm::test, vm::remove)
}

@Composable
fun WebSearchContent(state: WebSearchUiState, onKeyChange: (String) -> Unit, onSave: () -> Unit, onTest: () -> Unit, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Web search", style = MaterialTheme.typography.titleMedium)
        Text(
            if (state.keySaved) {
                "Edith searches with Ollama's web search, and DuckDuckGo if that fails."
            } else {
                "Edith searches with DuckDuckGo — nothing to set up. Optional: an Ollama API key is more dependable " +
                    "(DuckDuckGo sometimes blocks automated searches), with DuckDuckGo kept as the backup."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = state.keyField,
            onValueChange = onKeyChange,
            label = { Text(if (state.keySaved) "Replace the Ollama API key" else "Ollama API key (optional)") },
            placeholder = { if (state.keySaved) Text("A key is saved") },
            supportingText = { Text("Create one at ollama.com → Settings → Keys. Stored encrypted on this phone only.") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTest, enabled = !state.testing && (state.keySaved || state.keyField.isNotBlank())) { Text("Test") }
            Button(onClick = onSave, enabled = state.keyField.isNotBlank()) { Text("Save key") }
            if (state.keySaved) TextButton(onClick = onRemove) { Text("Remove") }
        }
        state.status?.let {
            Text(it, color = if (state.statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }
}

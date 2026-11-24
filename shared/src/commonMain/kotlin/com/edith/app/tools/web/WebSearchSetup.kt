package com.edith.app.tools.web

import com.edith.app.core.secrets.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The optional Ollama API key (D3): web search works without it (DuckDuckGo); with it, Ollama's search comes first.
 * The key lives only in [SecretStore] (encrypted with the Android Keystore) — never in settings, logs, crash reports
 * or the repo.
 */
class WebSearchSetup(private val secrets: SecretStore) {
    private val _keySaved = MutableStateFlow(false)
    val keySaved: StateFlow<Boolean> = _keySaved.asStateFlow()

    /** Reads whether a key is stored: when Settings opens, and after every change. */
    suspend fun refresh() {
        _keySaved.value = key() != null
    }

    suspend fun key(): String? = secrets.get(SECRET_NAME)?.takeIf { it.isNotBlank() }

    /** Saves a new key, or removes it when [key] is blank. */
    suspend fun save(key: String?) {
        secrets.put(SECRET_NAME, key?.trim()?.takeIf { it.isNotEmpty() })
        refresh()
    }

    private companion object {
        const val SECRET_NAME = "ollama_api_key"
    }
}

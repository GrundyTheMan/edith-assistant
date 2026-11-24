package com.edith.app.diagnostics

import com.edith.app.core.error.Outcome
import com.edith.app.core.secrets.SecretStore
import com.edith.app.core.time.SystemClock
import com.edith.app.di.SharedContainer
import com.edith.app.settings.SettingsRepository

/**
 * A short self-test of the shared engine on the platform it runs on (K4.1): is the database there, does the Keychain keep a secret, are the
 * settings saved, and can the Ollama server be reached and asked for its models. The first iPhone build shows it, because nothing of the engine
 * has run on a real phone before; the same code runs in the desktop tests with pretend parts. It writes one empty chat if the database had none
 * (the engine always has a current conversation) and one secret it removes again; nothing else.
 */
class EngineCheck(
    private val container: SharedContainer,
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
) {
    data class Line(val name: String, val ok: Boolean, val detail: String)

    /** The address saved in the settings (what the owner last typed). */
    suspend fun savedServerUrl(): String = settings.current().serverUrl

    /** Saves [url] as the Ollama address, then runs every check against it. */
    suspend fun run(url: String): List<Line> {
        val address = url.trim()
        settings.update { it.copy(serverUrl = address) }
        return listOf(database(), keychain(), settingsFile(address), ollamaVersion(address), ollamaModels(address))
    }

    private suspend fun attempt(name: String, block: suspend () -> Line): Line =
        try {
            block()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Line(name, false, "${e::class.simpleName}: ${e.message}")
        }

    private suspend fun database() = attempt("Database") {
        val id = container.currentConversation.resolve()
        Line("Database", true, "opened; the current chat is number $id")
    }

    private suspend fun keychain() = attempt("Keychain") {
        val token = "check-" + SystemClock.now().toEpochMilliseconds()
        secrets.put(KEY, token)
        val back = secrets.get(KEY)
        secrets.put(KEY, null)
        val gone = secrets.get(KEY) == null
        Line("Keychain", back == token && gone, if (back == token && gone) "kept a secret, read it back, removed it" else "read back \"$back\", removed: $gone")
    }

    private suspend fun settingsFile(address: String) = attempt("Settings") {
        val saved = settings.current().serverUrl
        Line("Settings", saved == address, "the Ollama address is saved as $saved")
    }

    private suspend fun ollamaVersion(address: String) = attempt("Ollama") {
        when (val r = container.ollama.version(address)) {
            is Outcome.Success -> Line("Ollama", true, "version ${r.value}")
            is Outcome.Failure -> Line("Ollama", false, r.error.userMessage)
        }
    }

    private suspend fun ollamaModels(address: String) = attempt("Models") {
        when (val r = container.ollama.listModels(address)) {
            is Outcome.Success -> Line("Models", true, "${r.value.size} installed: " + r.value.take(4).joinToString { it.name } + if (r.value.size > 4) ", …" else "")
            is Outcome.Failure -> Line("Models", false, r.error.userMessage)
        }
    }

    private companion object {
        const val KEY = "engine-check"
    }
}

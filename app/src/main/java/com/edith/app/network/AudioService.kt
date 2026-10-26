package com.edith.app.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Transcribes recordings by sending them to the Ollama server.
 * The configured model must support audio input (e.g. gemma4).
 */
class AudioService(private val configManager: com.edith.app.utils.ConfigManager) {

    suspend fun transcribe(recording: File): String = withContext(Dispatchers.IO) {
        val audio = Base64.encodeToString(recording.readBytes(), Base64.NO_WRAP)
        NetworkClient.clear() // pick up any server URL change from Settings
        val api = NetworkClient.getOllamaApi(configManager.getOllamaUrl())
        val request = ChatRequest(
            model = configManager.getModelName(),
            messages = listOf(ChatMessage("user", TRANSCRIBE_PROMPT, images = listOf(audio))),
            think = false
        )
        val transcript = api.generateChat(request).message.content.trim()
        // An empty result means no speech; the model tends to apologise rather than return nothing
        if (transcript.contains(NO_SPEECH_MARKER)) "" else transcript
    }

    private companion object {
        const val NO_SPEECH_MARKER = "[NO_SPEECH]"
        const val TRANSCRIBE_PROMPT =
            "Transcribe this audio verbatim. Reply with only the transcript, no commentary. " +
            "If there is no intelligible speech, reply with exactly $NO_SPEECH_MARKER and nothing else."
    }
}

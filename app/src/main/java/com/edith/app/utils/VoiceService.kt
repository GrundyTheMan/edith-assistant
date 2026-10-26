package com.edith.app.utils

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import com.edith.app.network.NetworkClient
import com.edith.app.network.SpeechRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Reads Edith's replies aloud.
 * Asks the Ollama server for speech first; when it can't provide it (Ollama's speech models
 * currently need an Apple Silicon Mac), falls back to the phone's built-in text-to-speech.
 */
class VoiceService(private val context: Context, private val configManager: ConfigManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var speakJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
            } else {
                Log.e(TAG, "Phone text-to-speech failed to initialise")
            }
        }
    }

    fun speak(text: String) {
        if (!configManager.isSpeakRepliesEnabled()) return
        val spoken = cleanForSpeech(text)
        if (spoken.isBlank()) return

        stop()
        speakJob = scope.launch {
            val playedByOllama = try {
                playOllamaSpeech(spoken)
                true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.i(TAG, "Ollama speech unavailable (${e.message}); using the phone's voice")
                false
            }
            if (!playedByOllama) speakWithPhone(spoken)
        }
    }

    fun stop() {
        speakJob?.cancel()
        speakJob = null
        mediaPlayer?.run {
            stop()
            release()
        }
        mediaPlayer = null
        tts?.stop()
    }

    fun release() {
        stop()
        tts?.shutdown()
        scope.cancel()
    }

    private suspend fun playOllamaSpeech(text: String) {
        val audio = withContext(Dispatchers.IO) {
            val api = NetworkClient.getOllamaApi(configManager.getOllamaUrl())
            api.generateSpeech(SpeechRequest(model = VOICE_MODEL, input = text.take(MAX_SPEECH_CHARS))).bytes()
        }
        val file = File(context.cacheDir, "edith_reply.wav").apply { writeBytes(audio) }
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.path)
            setOnCompletionListener { player ->
                player.release()
                if (mediaPlayer === player) mediaPlayer = null
            }
            prepare()
            start()
        }
        Log.i(TAG, "Speaking with Ollama ($VOICE_MODEL)")
    }

    private fun speakWithPhone(text: String) {
        if (!ttsReady) {
            Log.e(TAG, "Phone text-to-speech isn't ready; reply not spoken")
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "edith-reply")
        Log.i(TAG, "Speaking with the phone's text-to-speech")
    }

    // Markdown symbols and emoji get read out literally, so strip them first
    private fun cleanForSpeech(text: String): String =
        text.replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
            .replace(Regex("""[*_#`>~|]"""), "")
            .replace(Regex("""\p{So}|️"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private companion object {
        const val TAG = "VoiceService"
        const val VOICE_MODEL = "phonon"
        const val MAX_SPEECH_CHARS = 4096 // Ollama's per-request limit
    }
}

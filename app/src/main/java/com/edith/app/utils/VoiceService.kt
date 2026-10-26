package com.edith.app.utils

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.edith.app.network.NetworkClient
import com.edith.app.network.SpeechRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Reads Edith's replies aloud.
 * Asks the server for speech first (/v1/audio/speech): Edith's voice server answers it with
 * Orpheus, and so does Ollama itself on a Mac (phonon). When the server can't provide speech,
 * falls back to the phone's built-in text-to-speech.
 */
class VoiceService(private val context: Context, private val configManager: ConfigManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var speakJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Identifies the current utterance, so callbacks from one that was stopped don't clear a newer one
    @Volatile private var currentUtteranceId: String? = null
    private var utteranceCount = 0

    private val _speakingText = MutableStateFlow<String?>(null)
    /** The reply text currently being spoken, or null when Edith is silent. */
    val speakingText: StateFlow<String?> = _speakingText

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = finished(utteranceId)
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finished(utteranceId)
                    override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
                })
                ttsReady = true
            } else {
                Log.e(TAG, "Phone text-to-speech failed to initialise")
            }
        }
    }

    /** Speaks a new reply automatically, if the user has that turned on in Settings. */
    fun speakReply(text: String) {
        if (configManager.isSpeakRepliesEnabled()) speak(text)
    }

    /** Speaks [text] now, replacing anything already playing. */
    fun speak(text: String) {
        val spoken = cleanForSpeech(text)
        if (spoken.isBlank()) return

        stop()
        val utteranceId = "edith-reply-${++utteranceCount}"
        Log.i(TAG, "Speaking $utteranceId (${spoken.length} chars)")
        currentUtteranceId = utteranceId
        _speakingText.value = text
        speakJob = scope.launch {
            val playedByOllama = try {
                playOllamaSpeech(spoken, utteranceId)
                true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.i(TAG, "Server speech unavailable (${e.message}); using the phone's voice")
                false
            }
            if (!playedByOllama) speakWithPhone(spoken, utteranceId)
        }
    }

    fun stop() {
        currentUtteranceId = null
        _speakingText.value = null
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

    private fun finished(utteranceId: String?) {
        if (utteranceId != null && utteranceId == currentUtteranceId) {
            currentUtteranceId = null
            _speakingText.value = null
        }
    }

    private suspend fun playOllamaSpeech(text: String, utteranceId: String) {
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
                finished(utteranceId)
            }
            prepare()
            start()
        }
        Log.i(TAG, "Speaking with the server's voice")
    }

    private fun speakWithPhone(text: String, utteranceId: String) {
        if (!ttsReady) {
            Log.e(TAG, "Phone text-to-speech isn't ready; reply not spoken")
            finished(utteranceId)
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
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
        const val VOICE_MODEL = "phonon" // used by Ollama itself; the voice server ignores it
        const val MAX_SPEECH_CHARS = 4096 // Ollama's per-request limit
    }
}

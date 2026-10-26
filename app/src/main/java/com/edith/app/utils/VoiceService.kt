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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

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
            val parts = splitForSpeech(spoken)
            val spokenParts = playServerSpeech(parts)
            ensureActive() // stopped while speaking: don't fall back to the phone's voice
            if (spokenParts < parts.size) {
                speakWithPhone(parts.drop(spokenParts).joinToString(" "), utteranceId)
            } else {
                finished(utteranceId)
            }
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

    /**
     * Plays [parts] in the server's voice, fetching each part's audio while the previous one plays.
     * Returns how many parts were spoken; fewer than all means the server couldn't provide speech.
     */
    private suspend fun playServerSpeech(parts: List<String>): Int = coroutineScope {
        // Results rather than exceptions, so a failed fetch doesn't cancel the part that's playing
        fun fetch(index: Int) = async(Dispatchers.IO) {
            runCatching {
                val api = NetworkClient.getOllamaApi(configManager.getOllamaUrl())
                api.generateSpeech(SpeechRequest(model = VOICE_MODEL, input = parts[index].take(MAX_SPEECH_CHARS))).bytes()
            }
        }

        var next = fetch(0)
        for (index in parts.indices) {
            val audio = next.await().getOrElse { e ->
                if (e is CancellationException) throw e
                Log.i(TAG, "Server speech unavailable (${e.message}); using the phone's voice")
                return@coroutineScope index
            }
            if (index + 1 < parts.size) next = fetch(index + 1)
            if (index == 0) Log.i(TAG, "Speaking with the server's voice (${parts.size} parts)")
            playAndWait(audio, index)
        }
        parts.size
    }

    private suspend fun playAndWait(audio: ByteArray, index: Int) {
        val file = File(context.cacheDir, "edith_reply_$index.wav").apply { writeBytes(audio) }
        suspendCancellableCoroutine { continuation ->
            val player = MediaPlayer()
            mediaPlayer = player
            fun done() {
                player.release()
                if (mediaPlayer === player) mediaPlayer = null
                if (continuation.isActive) continuation.resume(Unit)
            }
            player.setDataSource(file.path)
            player.setOnCompletionListener { done() }
            player.setOnErrorListener { _, _, _ -> done(); true }
            player.prepare()
            player.start()
            // stop() releases the player itself; nothing more to do on cancellation
        }
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

    // The first sentence goes alone so speech starts as soon as possible; the rest are grouped
    // into parts of up to ~200 characters to keep the pauses between parts few
    private fun splitForSpeech(text: String): List<String> {
        val sentences = Regex("""[^.!?]+[.!?]*["')\]]*""").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        if (sentences.isEmpty()) return listOf(text)
        val parts = mutableListOf<String>()
        // A long opening sentence is cut at its first natural pause so playback still starts quickly
        val opening = sentences.first()
        val pause = Regex("""[,;:—–]\s""").findAll(opening).map { it.range.first + 1 }
            .firstOrNull { it in MIN_OPENING_CHARS until opening.length - MIN_OPENING_CHARS }
        if (opening.length > MAX_OPENING_CHARS && pause != null) {
            parts += opening.substring(0, pause).trim()
            parts += opening.substring(pause).trim()
        } else {
            parts += opening
        }
        var current = ""
        for (sentence in sentences.drop(1)) {
            if (current.isNotEmpty() && current.length + sentence.length > MAX_PART_CHARS) {
                parts += current
                current = ""
            }
            current = if (current.isEmpty()) sentence else "$current $sentence"
        }
        if (current.isNotEmpty()) parts += current
        return parts
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
        const val MAX_PART_CHARS = 200
        const val MAX_OPENING_CHARS = 100
        const val MIN_OPENING_CHARS = 25
    }
}

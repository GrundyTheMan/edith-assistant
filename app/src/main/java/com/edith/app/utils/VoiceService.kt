package com.edith.app.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Reads Edith's replies aloud with the phone's built-in text-to-speech.
 * Runs entirely on the device: no network calls.
 */
class VoiceService(context: Context, private val configManager: ConfigManager) {
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
        val spoken = cleanForSpeech(text).take(TextToSpeech.getMaxSpeechInputLength())
        if (spoken.isBlank()) return
        if (!ttsReady) {
            Log.e(TAG, "Phone text-to-speech isn't ready; reply not spoken")
            return
        }

        stop()
        val utteranceId = "edith-reply-${++utteranceCount}"
        currentUtteranceId = utteranceId
        _speakingText.value = text
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Log.i(TAG, "Speaking $utteranceId with the phone's text-to-speech (${spoken.length} chars)")
    }

    fun stop() {
        currentUtteranceId = null
        _speakingText.value = null
        tts?.stop()
    }

    fun release() {
        stop()
        tts?.shutdown()
    }

    private fun finished(utteranceId: String?) {
        if (utteranceId != null && utteranceId == currentUtteranceId) {
            currentUtteranceId = null
            _speakingText.value = null
        }
    }

    // Attachments, markdown symbols and emoji would be read out literally, so strip them first
    private fun cleanForSpeech(text: String): String =
        ReplyFormat.forSpeech(text)
            .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
            .replace(Regex("""[*_#`>~|]"""), "")
            .replace(Regex("""\p{So}|️"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private companion object {
        const val TAG = "VoiceService"
    }
}

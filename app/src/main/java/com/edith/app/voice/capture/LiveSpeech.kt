package com.edith.app.voice.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag

/**
 * Android's speech recognizer, live from the microphone (C12 §3–4): dictation into the text field, and voice memos
 * when the model can't hear audio. (`SpeechRecognizer` can't transcribe a file on most devices, so that fallback
 * listens live instead of recording a WAV.) Main thread only, as `SpeechRecognizer` requires.
 */
class LiveSpeech(private val context: Context) : SpeechInput {
    private var recognizer: SpeechRecognizer? = null

    override val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    override fun start(listener: SpeechInput.Listener) {
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))   // about -2..10 dB
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let(listener::onPartial)
            }
            override fun onResults(results: Bundle?) {
                release()
                listener.onFinal(first(results).orEmpty())
            }
            override fun onError(error: Int) {
                release()
                Log.w(LogTag.Voice, "Speech recognizer error $error")
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) listener.onFinal("")
                else listener.onError(messageFor(error))
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true),
        )
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        recognizer?.cancel()
        release()
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun first(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        const val DIDNT_CATCH = SpeechInput.DIDNT_CATCH
        const val UNAVAILABLE = SpeechInput.UNAVAILABLE

        fun messageFor(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceMemos.MIC_DENIED
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                "Speech recognition needs a connection right now — try again."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy — try again in a moment."
            SpeechRecognizer.ERROR_AUDIO -> "The microphone didn't work — try again."
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> DIDNT_CATCH
            else -> UNAVAILABLE
        }
    }
}

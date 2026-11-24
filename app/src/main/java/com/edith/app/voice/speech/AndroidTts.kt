package com.edith.app.voice.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Android's [TextToSpeech] (C13 §1; A4: on the phone, no network). Started once for the app; speech asked for before
 * the engine is ready waits and is spoken when it is. The language is the phone's, else US English.
 */
class AndroidTts(context: Context) : TtsEngine {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                Log.d(LogTag.Voice, "Audio focus lost; stopping speech")
                listener?.onInterrupted()
            }
        }
        .build()

    private val _available = MutableStateFlow(true)   // until the engine says otherwise
    override val available: StateFlow<Boolean> = _available.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    override val problem: StateFlow<String?> = _problem.asStateFlow()

    @Volatile private var listener: TtsEngine.Listener? = null
    private val lock = Any()
    private var ready = false
    private var holdingFocus = false
    /** Speech asked for before the engine was ready. */
    private val waiting = mutableListOf<Triple<String, String, Float>>()

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) { listener?.onStart(utteranceId) }
        override fun onDone(utteranceId: String) { listener?.onDone(utteranceId) }
        override fun onStop(utteranceId: String, interrupted: Boolean) { listener?.onDone(utteranceId) }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) { listener?.onError(utteranceId) }
        override fun onError(utteranceId: String, errorCode: Int) {
            Log.w(LogTag.Voice, "Text-to-speech error $errorCode")
            listener?.onError(utteranceId)
        }
    }

    /** Last, so everything [onInit] uses exists even if Android reports failure from inside the constructor. */
    private val tts: TextToSpeech = TextToSpeech(app) { status -> onInit(status) }

    override fun setListener(listener: TtsEngine.Listener) {
        this.listener = listener
    }

    private fun onInit(status: Int) {
        val problem = if (status != TextToSpeech.SUCCESS) {
            "Speech isn't available — install a voice in Android settings (Text-to-speech output)."
        } else {
            tts.setAudioAttributes(attributes)
            tts.setOnUtteranceProgressListener(progress)
            chooseLanguage()
        }
        val queued: List<Triple<String, String, Float>>
        synchronized(lock) {
            ready = problem == null
            queued = waiting.toList()
            waiting.clear()
        }
        if (problem != null) {
            Log.w(LogTag.Voice, "Text-to-speech unavailable (status $status): $problem")
            _problem.value = problem
            _available.value = false
            queued.forEach { listener?.onError(it.second) }
        } else {
            Log.d(LogTag.Voice, "Text-to-speech ready: ${tts.defaultEngine}, voice ${tts.voice?.name}, network ${tts.voice?.isNetworkConnectionRequired}")
            queued.forEach { (text, id, rate) -> if (!speakNow(text, id, rate)) listener?.onError(id) }
        }
    }

    /** The phone's language, else US English. Returns why neither works, or null. */
    private fun chooseLanguage(): String? {
        for (locale in listOf(Locale.getDefault(), Locale.US).distinct()) {
            val result = tts.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                preferOfflineVoice()
                return null
            }
            Log.w(LogTag.Voice, "No text-to-speech voice for $locale ($result)")
        }
        return "Speech isn't available — install a voice in Android settings (Text-to-speech output)."
    }

    /** Speech never uses the network (A4): if the default voice is a network one, use an installed voice instead. */
    private fun preferOfflineVoice() {
        val current = tts.voice ?: return
        if (!current.isNetworkConnectionRequired) return
        val offline = tts.voices.orEmpty().firstOrNull {
            it.locale == current.locale && !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        }
        if (offline != null) tts.voice = offline
        else Log.w(LogTag.Voice, "Only a network voice is available for ${current.locale}")
    }

    override fun speak(text: String, utteranceId: String, rate: Float): Boolean {
        synchronized(lock) {
            if (!ready) {
                if (!_available.value) return false
                waiting += Triple(text, utteranceId, rate)
                return true
            }
        }
        return speakNow(text, utteranceId, rate)
    }

    private fun speakNow(text: String, utteranceId: String, rate: Float): Boolean {
        synchronized(lock) {
            if (!holdingFocus) holdingFocus = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        tts.setSpeechRate(rate)
        return tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), utteranceId) == TextToSpeech.SUCCESS
    }

    override fun stop() {
        synchronized(lock) { waiting.clear() }
        tts.stop()
        idle()
    }

    override fun idle() {
        synchronized(lock) {
            if (holdingFocus) audio.abandonAudioFocusRequest(focusRequest)
            holdingFocus = false
        }
    }
}

package com.edith.app.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.os.Bundle
import java.util.Locale

class SpeechManager(context: Context) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    init {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            } else {
                Log.e("SpeechManager", "TTS initialization failed")
            }
        }
    }

    fun isPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun startListening(onResult: (String) -> Unit) {
        if (!isPermissionGranted()) {
            Log.e("SpeechManager", "Permission not granted")
            return
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferResults(buffer: Bundle?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                Log.e("SpeechManager", "Error: $error")
            }
            override fun onResults(results: Bundle?) {
                val data = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!data.isNullOrEmpty()) {
                    onResult(data[0])
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onPriceUpdate(params: Bundle?) {}
            override fun onTimeout() {}
            override fun onTryAgain() {}
        })

        val intent = RecognizerIntent()
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US)

        speechRecognizer?.listen(intent)
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    fun release() {
        speechRecognizer?.destroy()
        tts?.shutdown()
    }
}

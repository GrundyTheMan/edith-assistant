@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.edith.app.voice.capture

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.setActive
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dictation into the text field on the iPhone (K4.3): Apple's `SFSpeechRecognizer` over the microphone. It asks its own permission the first time
 * (`NSSpeechRecognitionUsageDescription`, and the microphone's). Recognition stays on the phone when the language supports it; otherwise Apple's
 * servers do it, as with the keyboard's own dictation. Words appear as they are recognised ([SpeechInput.Listener.onPartial]), and the level meter
 * is fed from the same audio. Callbacks arrive on the main thread. Written without a Mac to try it on: CI compiles it, the owner's phone is the first real run.
 */
class IosSpeechInput : SpeechInput {
    private val recognizer: SFSpeechRecognizer? = SFSpeechRecognizer()
    private var engine: AVAudioEngine? = null
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null
    private var listener: SpeechInput.Listener? = null
    private var lastText = ""
    private var cancelled = false

    override val isAvailable: Boolean get() = recognizer?.isAvailable() == true

    override fun start(listener: SpeechInput.Listener) {
        if (this.listener != null) cancel()
        this.listener = listener
        cancelled = false
        lastText = ""
        SFSpeechRecognizer.requestAuthorization { status ->
            dispatch_async(dispatch_get_main_queue()) {
                if (status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) begin()
                else fail("Allow speech recognition for Edith in Settings to dictate.")
            }
        }
    }

    override fun stop() {
        stopAudio()
        request?.endAudio()   // the final result still arrives
    }

    override fun cancel() {
        cancelled = true
        task?.cancel()
        stopAudio()
        cleanup()
    }

    private fun begin() {
        val recognizer = recognizer?.takeIf { it.isAvailable() } ?: return fail(SpeechInput.UNAVAILABLE)
        if (listener == null || cancelled) return
        val session = AVAudioSession.sharedInstance()
        if (!session.setCategory(AVAudioSessionCategoryRecord, AVAudioSessionModeMeasurement, 0uL, null) || !session.setActive(true, null)) {
            return fail("Edith couldn't use the microphone.")
        }
        val req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        if (recognizer.supportsOnDeviceRecognition) req.requiresOnDeviceRecognition = true
        val audio = AVAudioEngine()
        val input = audio.inputNode
        input.installTapOnBus(0u, 1024u, input.outputFormatForBus(0u)) { buffer, _ ->
            if (buffer == null) return@installTapOnBus
            req.appendAudioPCMBuffer(buffer)
            val samples = buffer.floatChannelData?.get(0)
            val n = buffer.frameLength.toInt()
            if (samples != null && n > 0) {
                var sum = 0f
                for (i in 0 until n) { val v = samples[i]; sum += v * v }
                val level = min(1f, sqrt(sum / n) * 8f)
                dispatch_async(dispatch_get_main_queue()) { listener?.onLevel(level) }
            }
        }
        audio.prepare()
        if (!audio.startAndReturnError(null)) {
            input.removeTapOnBus(0u)
            return fail("Edith couldn't start listening.")
        }
        engine = audio
        request = req
        task = recognizer.recognitionTaskWithRequest(req) { result, error ->
            val text = result?.bestTranscription?.formattedString.orEmpty()
            val final = result?.isFinal() == true
            dispatch_async(dispatch_get_main_queue()) { onResult(text, final, error != null) }
        }
    }

    private fun onResult(text: String, final: Boolean, failed: Boolean) {
        val l = listener ?: return
        if (cancelled) return
        if (text.isNotEmpty()) { lastText = text; if (!final) l.onPartial(text) }
        if (final || failed) {
            val finalText = text.ifEmpty { lastText }
            stopAudio()
            cleanup()
            if (finalText.isEmpty() && failed) l.onError(SpeechInput.DIDNT_CATCH) else l.onFinal(finalText)
        }
    }

    private fun fail(message: String) {
        val l = listener
        Log.w(LogTag.Voice, "Dictation: $message")
        cleanup()
        l?.onError(message)
    }

    private fun stopAudio() {
        engine?.let {
            it.stop()
            it.inputNode.removeTapOnBus(0u)
        }
        engine = null
        AVAudioSession.sharedInstance().setActive(false, null)
    }

    private fun cleanup() {
        task = null
        request = null
        listener = null
    }
}

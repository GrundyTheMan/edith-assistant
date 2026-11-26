@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.edith.app.voice.handsfree

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.setActive
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * "Hey Edith" on the iPhone (K6.1), found with Apple's own speech recognizer instead of a wake-word model (the ONNX Runtime Swift package is gone, D41 paused).
 * It listens only while Edith is open and hands-free is on, and **only on the phone**: if the language has no on-device recognition it refuses, because a wake
 * word would otherwise send everything said near the phone to Apple's servers. A recognition session lasts about a minute, so it is restarted before that, over
 * the same microphone. The words are matched by [WakePhrase]. Uses the play-and-record audio mode so Edith's own voice keeps playing while it listens for a barge-in.
 * Written without a Mac to try it on: CI compiles it; the owner's phone is the first real run, and how often it mishears "Edith" is only known there.
 */
class IosSpeechWakeListener : WakeListener {
    private val recognizer: SFSpeechRecognizer? = SFSpeechRecognizer()
    private var engine: AVAudioEngine? = null
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null
    private var onWake: (() -> Unit)? = null
    private var onFailed: (() -> Unit)? = null
    private var generation = 0
    private var consumed = 0
    private var loose = true
    private var startedAt = kotlin.time.TimeSource.Monotonic.markNow()

    override fun start(threshold: Float, onWake: () -> Unit, onFailed: () -> Unit): Boolean {
        stop()
        val r = recognizer
        if (r == null || !r.isAvailable() || !r.supportsOnDeviceRecognition) {
            Log.w(LogTag.Voice, "Wake word: no on-device speech recognition for this language")
            return false
        }
        this.onWake = onWake
        loose = WakePhrase.isLoose((0.9f - threshold) / 0.8f)   // the threshold is the sensitivity turned around (WakeWordDetector.threshold)
        this.onFailed = onFailed
        val gen = ++generation
        SFSpeechRecognizer.requestAuthorization { status ->
            dispatch_async(dispatch_get_main_queue()) {
                if (gen != generation) return@dispatch_async
                if (status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) begin(gen) else fail(gen)
            }
        }
        return true
    }

    override fun stop() {
        generation++
        task?.cancel()
        teardown()
        onWake = null
        onFailed = null
    }

    private fun begin(gen: Int) {
        val session = AVAudioSession.sharedInstance()
        if (!session.setCategory(AVAudioSessionCategoryPlayAndRecord, AVAudioSessionModeDefault, AVAudioSessionCategoryOptionDefaultToSpeaker, null) || !session.setActive(true, null)) return fail(gen)
        val audio = AVAudioEngine()
        val input = audio.inputNode
        input.installTapOnBus(0u, 1024u, input.outputFormatForBus(0u)) { buffer, _ ->
            if (buffer != null) request?.appendAudioPCMBuffer(buffer)
        }
        audio.prepare()
        if (!audio.startAndReturnError(null)) {
            input.removeTapOnBus(0u)
            return fail(gen)
        }
        engine = audio
        newRecognition(gen)
    }

    /** A fresh recognition session over the running microphone; the old one is ended first. */
    private fun newRecognition(gen: Int) {
        if (gen != generation) return
        val r = recognizer ?: return fail(gen)
        task?.cancel()
        request?.endAudio()
        consumed = 0
        val req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        req.requiresOnDeviceRecognition = true
        request = req
        startedAt = kotlin.time.TimeSource.Monotonic.markNow()
        task = r.recognitionTaskWithRequest(req) { result, error ->
            val text = result?.bestTranscription?.formattedString.orEmpty()
            val ended = result?.isFinal() == true || error != null
            dispatch_async(dispatch_get_main_queue()) { onResult(gen, req, text, ended) }
        }
    }

    private fun onResult(gen: Int, req: SFSpeechAudioBufferRecognitionRequest, text: String, ended: Boolean) {
        if (gen != generation || req !== request) return
        val end = WakePhrase.find(text, consumed, loose)
        if (end != null) {
            consumed = end
            val wake = onWake
            // The microphone goes back to the speech recognizer for the question: stop here first.
            stop()
            wake?.invoke()
            return
        }
        val age = startedAt.elapsedNow().inWholeSeconds
        if (ended || age >= SESSION_SECONDS) newRecognition(gen)
    }

    private fun fail(gen: Int) {
        if (gen != generation) return
        val failed = onFailed
        stop()
        failed?.invoke()
    }

    private fun teardown() {
        engine?.let {
            it.stop()
            it.inputNode.removeTapOnBus(0u)
        }
        engine = null
        request?.endAudio()
        request = null
        task = null
        AVAudioSession.sharedInstance().setActive(false, null)
    }

    private companion object {
        /** Apple ends a session at about a minute; restart before that. */
        const val SESSION_SECONDS = 50L
    }
}

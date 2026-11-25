@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.edith.app.voice.capture

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.util.deleteFileQuietly
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.setActive
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSTimer
import platform.Foundation.NSURL
import platform.Foundation.NSUUID

/**
 * Records the microphone into a WAV on the iPhone (K4.3): 16 kHz, mono, 16-bit, which is what Ollama's audio models take, with `AVAudioRecorder`.
 * The owner's permission is asked for before [start] (the chat screen does that), because `record()` just fails without it. The level meter is
 * polled ten times a second while recording, and the audio session is let go when it stops. Written without a Mac to try it on: the macOS jobs
 * compile it, and the owner's phone is the first real run.
 */
class IosRecorder(private val dir: String) : Recorder {
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()

    private var recorder: AVAudioRecorder? = null
    private var meter: NSTimer? = null
    private var path: String? = null

    override fun start(): Boolean {
        cancel()
        val files = NSFileManager.defaultManager
        files.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val target = "$dir/${NSUUID().UUIDString}.wav"
        val session = AVAudioSession.sharedInstance()
        if (!session.setCategory(AVAudioSessionCategoryRecord, AVAudioSessionModeMeasurement, 0uL, null) || !session.setActive(true, null)) {
            Log.w(LogTag.Voice, "The audio session could not be started for recording")
            return false
        }
        val settings: Map<Any?, *> = mapOf(
            AVFormatIDKey to NSNumber(int = kAudioFormatLinearPCM.toInt()),
            AVSampleRateKey to NSNumber(double = SAMPLE_RATE),
            AVNumberOfChannelsKey to NSNumber(int = 1),
            AVLinearPCMBitDepthKey to NSNumber(int = 16),
            AVLinearPCMIsFloatKey to NSNumber(bool = false),
            AVLinearPCMIsBigEndianKey to NSNumber(bool = false),
        )
        val made = runCatching { AVAudioRecorder(NSURL.fileURLWithPath(target), settings, null) }.getOrNull()
        if (made == null) {
            Log.w(LogTag.Voice, "The recorder could not be made")
            release()
            return false
        }
        made.meteringEnabled = true
        if (!made.record()) {
            Log.w(LogTag.Voice, "The recorder would not start (is the microphone allowed?)")
            release()
            return false
        }
        recorder = made
        path = target
        meter = NSTimer.scheduledTimerWithTimeInterval(0.1, true) { _ ->
            made.updateMeters()
            val db = made.averagePowerForChannel(0uL)   // -160 (silence) to 0 (loudest)
            _level.value = ((db + FLOOR_DB) / FLOOR_DB).coerceIn(0f, 1f)
        }
        return true
    }

    override fun stop(): RecordedAudio? {
        val r = recorder ?: return null
        val file = path ?: return null
        val ms = (r.currentTime * 1000).toLong()
        r.stop()
        finish()
        return RecordedAudio(file, ms)
    }

    override fun cancel() {
        val r = recorder ?: return
        val file = path
        r.stop()
        finish()
        file?.let(::deleteFileQuietly)
    }

    private fun finish() {
        meter?.invalidate()
        meter = null
        recorder = null
        path = null
        _level.value = 0f
        release()
    }

    private fun release() {
        AVAudioSession.sharedInstance().setActive(false, null)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000.0
        const val FLOOR_DB = 60f
    }
}

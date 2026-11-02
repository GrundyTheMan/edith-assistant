package com.edith.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.edith.app.EdithApp
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.voice.capture.WavWriter
import com.edith.app.voice.handsfree.OpenWakeWord
import com.edith.app.voice.handsfree.WakeWordDetector
import com.edith.app.voice.handsfree.HandsFree
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug builds only (C14 acceptance). The emulator's microphone only hears silence, so this is how speech gets into
 * hands-free. Only the shell can send it (DUMP permission). With hands-free on:
 *
 * - **`wake`**: scores a 16 kHz mono WAV in `cache/handsfree/` with the real wake-word models, logs the best score,
 *   and, if it reaches the threshold, acts as if the microphone heard it (the beep, then capturing):
 *
 *       adb push hey.wav /data/local/tmp/hey.wav
 *       adb shell "cat /data/local/tmp/hey.wav | run-as com.edith.app.debug sh -c 'mkdir -p cache/handsfree; cat > cache/handsfree/hey.wav'"
 *       adb shell am broadcast -n com.edith.app.debug/com.edith.app.debug.DebugHandsFreeReceiver --es wake hey.wav
 *
 * - **`say`**: stands in for what the speech recognizer heard: with `wake`, as soon as hands-free is capturing;
 *   alone, now (in a follow-up window):
 *
 *       adb shell am broadcast -n com.edith.app.debug/com.edith.app.debug.DebugHandsFreeReceiver --es wake hey.wav --es say "what\'s\ the\ weather\ in\ Boston"
 *       adb shell am broadcast -n com.edith.app.debug/com.edith.app.debug.DebugHandsFreeReceiver --es say "and\ tomorrow?"
 */
class DebugHandsFreeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as EdithApp).container
        val say = intent.getStringExtra("say")
        val name = intent.getStringExtra("wake")
        if (name == null) {
            say?.let { say(container, it) }
            return
        }
        val file = File(File(context.cacheDir, "handsfree"), name).takeIf { it.isFile && it.parentFile?.name == "handsfree" } ?: run {
            Log.w(LogTag.Voice, "DEBUG_HANDS_FREE: no such file: $name")
            return
        }
        val pending = goAsync()
        container.appScope.launch {
            try {
                val threshold = OpenWakeWord.threshold(container.settingsRepository.current().wakeWordSensitivity)
                val best = OpenWakeWord(context, container.wakeWordModel).use { bestScore(it, file) }
                val heard = best >= threshold
                Log.i(LogTag.Voice, "DEBUG_HANDS_FREE: $name best score ${"%.3f".format(best)} (threshold ${"%.2f".format(threshold)}) → ${if (heard) "heard" else "not heard"}")
                if (heard) withContext(container.dispatchers.main) {
                    container.handsFree.wakeWordHeard()
                    if (say != null) {
                        // The emulator's recognizer gives up on its silent mic within a second or two: say it as soon
                        // as hands-free is capturing.
                        withTimeoutOrNull(3_000) { container.handsFree.state.first { it is HandsFree.State.Capturing } }
                        say(container, say)
                    }
                }
            } catch (e: Exception) {
                Log.e(LogTag.Voice, "DEBUG_HANDS_FREE: couldn't score $name", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun say(container: com.edith.app.di.AppContainer, text: String) {
        Log.i(LogTag.Voice, "DEBUG_HANDS_FREE: heard \"$text\" (state ${container.handsFree.state.value})")
        container.handsFree.heard(text)
    }

    /** The highest score over the file, 80 ms at a time, with a second of silence after it (the model looks back 1.3 s). */
    private fun bestScore(detector: WakeWordDetector, file: File): Float {
        val bytes = file.readBytes()
        // The samples start after the "data" chunk's 8-byte header; it isn't always at byte 36 (Windows writes 38).
        val data = (12 until minOf(bytes.size - 8, 200)).firstOrNull { String(bytes, it, 4, Charsets.US_ASCII) == "data" }
            ?.plus(8) ?: WavWriter.HEADER_BYTES
        val pcm = ByteBuffer.wrap(bytes, data, bytes.size - data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(pcm.remaining() + WakeWordDetector.SAMPLE_RATE).also { pcm.get(it, 0, pcm.remaining()) }
        var best = 0f
        val chunk = ShortArray(WakeWordDetector.CHUNK_SAMPLES)
        var at = 0
        while (at + chunk.size <= samples.size) {
            samples.copyInto(chunk, 0, at, at + chunk.size)
            best = maxOf(best, detector.score(chunk))
            at += chunk.size
        }
        return best
    }
}

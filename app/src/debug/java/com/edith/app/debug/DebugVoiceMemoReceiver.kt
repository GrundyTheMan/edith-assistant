package com.edith.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.edith.app.EdithApp
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.voice.capture.RecordedAudio
import com.edith.app.voice.capture.WavWriter
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug builds only (C12 acceptance). Sends a WAV as a voice memo to the open chat, as if it had just been recorded:
 * the same transcription and sending as a real memo, and the file is deleted afterwards. The emulator's microphone
 * only hears silence, so this is how real speech gets in. Only the shell can send it (DUMP permission):
 *
 *   adb push memo.wav /data/local/tmp/memo.wav
 *   adb shell "cat /data/local/tmp/memo.wav | run-as com.edith.app.debug sh -c 'mkdir -p cache/recordings; cat > cache/recordings/debug.wav'"
 *   adb shell am broadcast -n com.edith.app.debug/com.edith.app.debug.DebugVoiceMemoReceiver --es file debug.wav
 */
class DebugVoiceMemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("file") ?: return
        val file = File(File(context.cacheDir, "recordings"), name).takeIf { it.isFile && it.parentFile?.name == "recordings" } ?: run {
            Log.w(LogTag.Voice, "DEBUG_VOICE_MEMO: no such recording: $name")
            return
        }
        val container = (context.applicationContext as EdithApp).container
        val conversationId = container.currentConversation.id.value ?: return
        val durationMs = (file.length() - WavWriter.HEADER_BYTES) * 1_000 / (WavWriter.SAMPLE_RATE * WavWriter.BYTES_PER_SAMPLE)
        Log.i(LogTag.Voice, "DEBUG_VOICE_MEMO: sending $name ($durationMs ms) to conversation $conversationId")
        container.appScope.launch(container.dispatchers.main) {
            container.voiceMemos.sendRecording(conversationId, RecordedAudio(file, durationMs))
        }
    }
}

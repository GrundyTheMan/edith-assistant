@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package spike

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import kotlin.coroutines.resume

actual suspend fun recordMemo(seconds: Int): RecordResult {
    val session = AVAudioSession.sharedInstance()
    val granted = suspendCancellableCoroutine<Boolean> { cont -> session.requestRecordPermission { ok -> cont.resume(ok) } }
    if (!granted) return RecordResult.Failed("The microphone isn't allowed. Turn it on in Settings.")
    val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + "spike-memo.m4a")
    return memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        if (!session.setCategory(AVAudioSessionCategoryRecord, error.ptr)) {
            return@memScoped RecordResult.Failed(error.value?.localizedDescription ?: "Couldn't set up the audio session.")
        }
        // (AVAudioSession.setActive is not exposed to Kotlin here; AVAudioRecorder activates the session itself when it starts. A Swift seam would call it.)
        // 1633772320 is the code for AAC ('aac '): the format the iPhone records a voice memo in.
        val settings = mapOf<Any?, Any?>(AVFormatIDKey to 1633772320, AVSampleRateKey to 16000.0, AVNumberOfChannelsKey to 1)
        val recorder = AVAudioRecorder(url, settings, error.ptr)
        if (!recorder.prepareToRecord() || !recorder.record()) {
            return@memScoped RecordResult.Failed(error.value?.localizedDescription ?: "The recorder would not start.")
        }
        delay(seconds * 1000L)
        recorder.stop()
        val size = NSData.dataWithContentsOfURL(url)?.length?.toLong() ?: 0L
        if (size > 0) RecordResult.Recorded(size, seconds) else RecordResult.Failed("Nothing was recorded.")
    }
}

actual fun autoProbe(): Boolean = NSProcessInfo.processInfo.environment["SPIKE_AUTOPROBE"] == "1"

actual fun chatProbeUrl(): String? = NSProcessInfo.processInfo.environment["SPIKE_CHAT_URL"] as String?

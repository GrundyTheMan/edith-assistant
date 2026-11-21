package spike

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

actual suspend fun recordMemo(seconds: Int): RecordResult = withContext(Dispatchers.IO) {
    try {
        val format = AudioFormat(16000f, 16, 1, true, false)
        val line = AudioSystem.getTargetDataLine(format)
        line.open(format)
        line.start()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(3200)
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            val n = line.read(buffer, 0, buffer.size)
            if (n > 0) out.write(buffer, 0, n)
        }
        line.stop()
        line.close()
        RecordResult.Recorded(out.size().toLong(), seconds)
    } catch (e: Exception) {
        RecordResult.Failed(e.message ?: "No microphone.")
    }
}

actual fun autoProbe(): Boolean = System.getenv("SPIKE_AUTOPROBE") == "1"

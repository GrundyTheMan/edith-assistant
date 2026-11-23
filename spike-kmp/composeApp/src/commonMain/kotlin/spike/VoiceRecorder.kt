package spike

/** What a recording came to. */
sealed interface RecordResult {
    data class Recorded(val bytes: Long, val seconds: Int) : RecordResult
    data class Failed(val why: String) : RecordResult
}

/**
 * Records the microphone for [seconds] and says how big the recording is. This is the spike's second native probe: on the iPhone it is the
 * microphone permission plus AVAudioSession and AVAudioRecorder written in Kotlin; on the desktop it is Java's sound API.
 */
expect suspend fun recordMemo(seconds: Int): RecordResult

/** True when the app was started with the probe switched on (the cloud build starts it that way, so a screenshot shows the result with no one tapping). */
expect fun autoProbe(): Boolean

/** The address of a fake streaming chat server the cloud build starts, or null when there is none (an ordinary run). */
expect fun chatProbeUrl(): String?

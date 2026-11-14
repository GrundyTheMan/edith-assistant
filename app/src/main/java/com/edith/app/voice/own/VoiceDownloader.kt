package com.edith.app.voice.own

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Where Edith's own voice is (C36 §3). */
sealed interface OwnVoiceState {
    data object NotDownloaded : OwnVoiceState
    /** [waitingForScreen]: paused until Edith is on screen again (Android blocks the network in the background, L36). */
    data class Downloading(val percent: Int, val waitingForScreen: Boolean = false) : OwnVoiceState
    data object Verifying : OwnVoiceState
    data object Unpacking : OwnVoiceState
    data class Ready(val sizeBytes: Long) : OwnVoiceState
    data class Failed(val message: String) : OwnVoiceState
}

/**
 * Downloads, checks and unpacks the voice, one tap at a time (C36 §3). The flow:
 *  1. at least twice the file's size must be free;
 *  2. stream to `voices/<id>.part`, resuming with an HTTP `Range` after a cut or after Edith was off screen;
 *  3. the SHA-256 must match the pinned one: a mismatch deletes the file (nothing in it is used before this passes);
 *  4. unpack into `voices/<id>.tmp/` ([VoiceArchive]);
 *  5. rename it to `voices/<id>/`: a kill during any of this leaves no half voice.
 *
 * It only uses the network while Edith is on screen ([visible]; lesson L36): leaving pauses it, coming back resumes.
 * No foreground service.
 */
class VoiceDownloader(
    private val voice: OwnVoice,
    /** `filesDir/voices`. */
    private val root: File,
    private val http: () -> Call.Factory,
    private val visible: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val freeBytes: () -> Long = { root.apply { mkdirs() }.usableSpace },
    /** Tries again this many times when the connection drops, after [retryDelayMs]. */
    private val maxRetries: Int = 3,
    private val retryDelayMs: Long = 2_000,
) {
    /** The finished voice. */
    val dir: File get() = File(root, voice.id)
    private val part = File(root, "${voice.id}.part")
    private val tmp = File(root, "${voice.id}.tmp")

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<OwnVoiceState> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var call: Call? = null

    private fun initialState(): OwnVoiceState =
        if (isInstalled()) OwnVoiceState.Ready(directorySize(dir)) else OwnVoiceState.NotDownloaded

    private fun isInstalled() =
        File(dir, OwnVoice.MODEL).isFile && File(dir, OwnVoice.TOKENS).isFile && File(dir, OwnVoice.ESPEAK_DATA).isDirectory

    /** Starts (or resumes) the download. Does nothing while one is running or the voice is ready. */
    @Synchronized fun start() {
        if (job?.isActive == true || _state.value is OwnVoiceState.Ready) return
        _state.value = OwnVoiceState.Downloading(percentOf(part.length()))
        job = scope.launch(io) {
            try {
                run()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(LogTag.Voice, "Voice download failed", e)
                _state.value = OwnVoiceState.Failed(GENERIC_FAILURE)
            }
        }
    }

    /** Stops a download in progress and throws away what was fetched. */
    @Synchronized fun cancel() {
        job?.cancel()
        job = null
        call?.cancel()
        part.delete()
        tmp.deleteRecursively()
        _state.value = if (isInstalled()) OwnVoiceState.Ready(directorySize(dir)) else OwnVoiceState.NotDownloaded
    }

    /** Removes the voice (and anything half downloaded). */
    @Synchronized fun delete() {
        job?.cancel()
        job = null
        call?.cancel()
        part.delete()
        tmp.deleteRecursively()
        dir.deleteRecursively()
        _state.value = OwnVoiceState.NotDownloaded
    }

    // --- The work ---------------------------------------------------------------------------------------------------

    private suspend fun run() {
        root.mkdirs()
        val need = 2 * voice.bytes - part.length()
        if (freeBytes() < need) {
            _state.value = OwnVoiceState.Failed("Not enough space: I need about ${(2 * voice.bytes + 999_999) / 1_000_000} MB free.")
            return
        }
        var failures = 0
        while (part.length() != voice.bytes) {
            currentCoroutineContext().ensureActive()
            if (!visible.value) {
                _state.value = OwnVoiceState.Downloading(percentOf(part.length()), waitingForScreen = true)
                visible.first { it }
            }
            _state.value = OwnVoiceState.Downloading(percentOf(part.length()))
            when (val result = fetch()) {
                Fetch.Complete, Fetch.LeftScreen -> {}
                is Fetch.Dropped -> {
                    if (++failures > maxRetries) {
                        _state.value = OwnVoiceState.Failed(result.message)
                        return
                    }
                    delay(retryDelayMs)
                }
                is Fetch.Refused -> {
                    _state.value = OwnVoiceState.Failed(result.message)
                    return
                }
            }
        }
        _state.value = OwnVoiceState.Verifying
        val actual = withContext(io) { sha256(part) }
        if (actual != voice.sha256) {
            part.delete()
            Log.w(LogTag.Voice, "Voice download didn't match its SHA-256")
            _state.value = OwnVoiceState.Failed(MISMATCH)
            return
        }
        _state.value = OwnVoiceState.Unpacking
        try {
            withContext(io) {
                VoiceArchive.extract(part, tmp)
                dir.deleteRecursively()
                if (!tmp.renameTo(dir)) throw IOException("Couldn't move the voice into place")
            }
        } catch (e: IOException) {
            Log.w(LogTag.Voice, "Voice couldn't be unpacked", e)
            tmp.deleteRecursively()
            part.delete()
            _state.value = OwnVoiceState.Failed(if (e is BadVoiceArchive) UNEXPECTED else "I couldn't unpack the voice. Try again.")
            return
        }
        part.delete()
        _state.value = OwnVoiceState.Ready(directorySize(dir))
    }

    private sealed interface Fetch {
        data object Complete : Fetch
        data object LeftScreen : Fetch
        /** The connection broke; worth another try. */
        data class Dropped(val message: String) : Fetch
        /** The server said no. */
        data class Refused(val message: String) : Fetch
    }

    /** One request, from where [part] ends to the end of the file (or until the connection breaks or Edith leaves). */
    private suspend fun fetch(): Fetch {
        var have = part.length()
        if (have > voice.bytes) {
            part.delete()
            have = 0
        }
        val request = Request.Builder().url(voice.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        val newCall = http().newCall(request)
        call = newCall
        val leftScreen = AtomicBoolean(false)
        return coroutineScope {
            // Leaving the screen, and cancelling the download (Cancel, Delete), must cut the blocking read at once.
            // Unconfined: these run on whichever thread cancels or changes [visible], not on a busy IO thread.
            val watchers = launch(Dispatchers.Unconfined) {
                launch {
                    visible.first { !it }
                    leftScreen.set(true)
                    newCall.cancel()
                }
                try { awaitCancellation() } finally { newCall.cancel() }
            }
            try {
                download(newCall, have, leftScreen)
            } finally {
                watchers.cancel()
                call = null
            }
        }
    }

    private suspend fun download(newCall: Call, have: Long, leftScreen: AtomicBoolean): Fetch {
        run {
            return withContext(io) {
                try {
                    newCall.execute().use { response ->
                        when {
                            response.code == 416 -> {   // what we have is no use to the server: start again
                                part.delete()
                                Fetch.Dropped(NO_NETWORK)
                            }
                            !response.isSuccessful -> Fetch.Refused("The download failed (error ${response.code}). Try again later.")
                            else -> {
                                // 200 to a Range request means the server sent the whole file again.
                                val append = response.code == 206 && have > 0
                                val body = response.body
                                val sink = java.io.FileOutputStream(part, append)
                                var written = if (append) have else 0L
                                var lastPercent = -1
                                sink.use { out ->
                                    val buffer = ByteArray(64 * 1024)
                                    val input = body.byteStream()
                                    while (written < voice.bytes) {
                                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), voice.bytes - written).toInt())
                                        if (n < 0) break
                                        out.write(buffer, 0, n)
                                        written += n
                                        val percent = percentOf(written)
                                        if (percent != lastPercent) {
                                            lastPercent = percent
                                            _state.value = OwnVoiceState.Downloading(percent)
                                        }
                                    }
                                }
                                if (written >= voice.bytes) Fetch.Complete else Fetch.Dropped(NO_NETWORK)
                            }
                        }
                    }
                } catch (e: IOException) {
                    if (leftScreen.get()) Fetch.LeftScreen else Fetch.Dropped(NO_NETWORK)
                }
            }.let { if (leftScreen.get() && it !is Fetch.Complete) Fetch.LeftScreen else it }
        }
    }

    private fun percentOf(bytes: Long): Int = (bytes * 100 / voice.bytes).toInt().coerceIn(0, 100)

    companion object {
        const val NO_NETWORK = "I couldn't reach the download. Check the network and try again."
        const val MISMATCH = "The file didn't match what I expected. Try again."
        const val UNEXPECTED = "The voice file wasn't what I expected. Try again."
        const val GENERIC_FAILURE = "Something went wrong downloading the voice. Try again."

        fun directorySize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

package com.edith.app.blink.hls

import com.edith.app.core.util.PlatformLock
import com.edith.app.core.util.synchronized
import com.edith.app.home.StreamLiveFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * A live feed's video as HLS held in memory (K4.17), for a player that wants a playlist and segments, not a byte stream: it reads [feed] on its own
 * coroutine, cuts the transport stream into segments and keeps the last few. The player's loader asks [playlist] and [segment] as often as it likes.
 * [close] stops reading and ends the playlist, so a player knows the stream is over.
 */
class HlsLiveSession(private val feed: StreamLiveFeed, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
    private val lock = PlatformLock()
    private val clock = TimeSource.Monotonic.markNow()
    private val segmenter = TsSegmenter(now = { clock.elapsedNow().inWholeMilliseconds })
    private val list = HlsPlaylist()
    private var bytesIn = 0L
    private var segmentsCut = 0
    private var recent = ArrayList<String>()
    private val job: Job = scope.launch {
        try {
            for (chunk in feed.video) {
                val cut = segmenter.push(chunk)
                lock.synchronized { bytesIn += chunk.size; segmentsCut += cut.size; cut.forEach { list.add(it); recent += "${it.seconds.toString().take(4)}s/${it.data.size / 1024}KB"; if (recent.size > 3) recent.removeAt(0) } }
            }
        } finally {
            lock.synchronized { segmenter.finish()?.let(list::add); list.end() }
        }
    }

    /** The playlist as text, or null before the first segment is ready. */
    fun playlist(): String? = lock.synchronized { if (list.isEmpty) null else list.render() }

    /** The bytes of segment [sequence], or null when it is not (or no longer) there. */
    fun segment(sequence: Long): ByteArray? = lock.synchronized { list.segment(sequence)?.data }

    /** One line for the screen while the live view is being proven on a phone: what has come in from the camera, and why the feed ended if it did. */
    fun status(): String {
        val (bytes, segments, last) = lock.synchronized { Triple(bytesIn, segmentsCut, recent.joinToString(" ")) }
        return "Camera: ${bytes / 1024} KB received, $segments segments cut" + (if (last.isEmpty()) "" else " (latest $last)") + (segmenter.streams.takeIf { it.isNotEmpty() }?.let { ". Stream: $it" } ?: "") + (feed.ended.value?.let { ". Ended: $it" } ?: "")
    }

    /** Stops reading the feed. */
    fun close() { job.cancel() }
}

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
    private val job: Job = scope.launch {
        try {
            for (chunk in feed.video) {
                val cut = segmenter.push(chunk)
                if (cut.isNotEmpty()) lock.synchronized { cut.forEach(list::add) }
            }
        } finally {
            lock.synchronized { segmenter.finish()?.let(list::add); list.end() }
        }
    }

    /** The playlist as text, or null before the first segment is ready. */
    fun playlist(): String? = lock.synchronized { if (list.isEmpty) null else list.render() }

    /** The bytes of segment [sequence], or null when it is not (or no longer) there. */
    fun segment(sequence: Long): ByteArray? = lock.synchronized { list.segment(sequence)?.data }

    /** Stops reading the feed. */
    fun close() { job.cancel() }
}

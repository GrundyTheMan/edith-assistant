package com.edith.app.blink

import com.edith.app.blink.hls.HlsLiveSession
import com.edith.app.home.StreamLiveFeed
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** K4.17: a feed's video becomes a playlist and segments that a player can fetch. */
class HlsLiveSessionTest {
    private class Feed : StreamLiveFeed {
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        override val video: ReceiveChannel<ByteArray> = channel
        override val ended: StateFlow<String?> = MutableStateFlow(null)
        override fun stop() { channel.close() }
    }

    private fun packet(pid: Int, start: Boolean): ByteArray {
        val p = ByteArray(188)
        p[0] = 0x47
        p[1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte()
        p[2] = (pid and 0xff).toByte()
        p[3] = 0x10
        return p
    }

    @Test fun theVideoEndsUpAsAPlaylistAndSegmentsAndEndsWhenTheFeedCloses() = runBlocking {
        val feed = Feed()
        val session = HlsLiveSession(feed)
        assertNull(session.playlist())
        feed.channel.send(packet(0, true) + packet(0x101, false) + packet(0x101, false))
        delay(100)
        assertNull(session.playlist())            // still the first, unfinished segment
        feed.channel.close()
        withTimeout(5_000) { while (session.playlist() == null) delay(10) }
        val text = assertNotNull(session.playlist())
        assertTrue(text.contains("seg0.ts"), text)
        assertTrue(text.endsWith("#EXT-X-ENDLIST\n"), text)
        assertEquals(3 * 188, assertNotNull(session.segment(0)).size)
        assertNull(session.segment(5))
        session.close()
    }
}

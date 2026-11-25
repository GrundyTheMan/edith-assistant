package com.edith.app.blink

import com.edith.app.blink.hls.HlsPlaylist
import com.edith.app.blink.hls.TsSegmenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** K4.17: cutting a transport stream into HLS segments and the playlist for them. */
class TsSegmenterTest {
    private fun packet(pid: Int, start: Boolean, fill: Int = 0): ByteArray {
        val p = ByteArray(188) { fill.toByte() }
        p[0] = 0x47
        p[1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte()
        p[2] = (pid and 0xff).toByte()
        p[3] = 0x10
        return p
    }

    /** A PAT packet naming program 1 with PMT pid [pmt]. */
    private fun pat(pmt: Int): ByteArray {
        val p = packet(0, true, 0xff)
        val section = byteArrayOf(0x00, 0xb0.toByte(), 13, 0, 1, 0xc1.toByte(), 0, 0, 0, 1, (0xe0 or (pmt shr 8)).toByte(), (pmt and 0xff).toByte(), 0, 0, 0, 0)
        p[4] = 0                                   // pointer
        section.copyInto(p, 5)
        return p
    }

    @Test fun cutsAtAPatOnceLongEnoughAndEachSegmentStartsWithTheTables() {
        var t = 0L
        val s = TsSegmenter(now = { t })
        val pmt = packet(0x100, true, 0x22)
        val video = packet(0x101, false, 0x33)
        assertTrue(s.push(pat(0x100) + pmt + video + video).isEmpty())
        t = 1_000
        assertTrue(s.push(pat(0x100) + video).isEmpty())          // too short: no cut
        t = 2_500
        val out = s.push(pat(0x100) + video)                       // long enough, at a PAT
        assertEquals(1, out.size)
        assertEquals(0, out[0].sequence)
        assertEquals(2.5, out[0].seconds)
        assertEquals(0, out[0].data.size % 188)
        t = 5_000
        val second = s.push(pat(0x100) + video)
        assertEquals(1, second.size)
        // The second segment opened with the PAT, then the remembered PMT.
        val d = second[0].data
        assertEquals(0, d[1].toInt() and 0x1f); assertEquals(0, d[2].toInt())
        assertEquals(0x01, d[188 + 1].toInt() and 0x1f); assertEquals(0x00, d[188 + 2].toInt())
    }

    @Test fun cutsAtTheMaximumWithoutAPat() {
        var t = 0L
        val s = TsSegmenter(now = { t }, minMs = 2_000, maxMs = 6_000)
        val video = packet(0x101, false)
        s.push(pat(0x100) + packet(0x100, true) + video)
        t = 6_500
        assertEquals(1, s.push(video + video).size)
    }

    @Test fun packetsSplitAcrossPushesAndGarbageBeforeSyncAreHandled() {
        val t = 0L
        val s = TsSegmenter(now = { t })
        val stream = byteArrayOf(1, 2, 3) + pat(0x100) + packet(0x100, true) + packet(0x101, false)
        s.push(stream.copyOfRange(0, 100)); s.push(stream.copyOfRange(100, 400)); s.push(stream.copyOfRange(400, stream.size))
        val last = assertNotNull(s.finish())
        assertEquals(3 * 188, last.data.size)
        assertNull(s.finish())
    }

    @Test fun playlistSlidesAndEnds() {
        var t = 0L
        val s = TsSegmenter(now = { t })
        val list = HlsPlaylist(window = 2)
        val video = packet(0x101, false)
        repeat(5) { i -> t = i * 3_000L; s.push(pat(0x100) + video).forEach(list::add) }
        val text = list.render()
        assertTrue(text.startsWith("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:3\n#EXT-X-MEDIA-SEQUENCE:"), text)
        assertEquals(2, Regex("#EXTINF").findAll(text).count())
        assertTrue(text.contains("#EXTINF:3.000,"), text)
        assertTrue(!text.contains("ENDLIST"))
        list.end()
        assertTrue(list.render().endsWith("#EXT-X-ENDLIST\n"))
        assertNotNull(list.segment(3)); assertNull(list.segment(0))
    }
}

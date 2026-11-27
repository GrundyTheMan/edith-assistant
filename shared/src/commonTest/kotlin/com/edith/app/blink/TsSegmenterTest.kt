package com.edith.app.blink

import com.edith.app.blink.hls.AudioKeep
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

/** What the camera's stream holds, read from its PMT (shown on the live view's diagnostics). */
class TsSegmenterStreamsTest {
    private fun pmtPacket(vararg types: Int): ByteArray {
        val p = ByteArray(188) { 0xff.toByte() }
        p[0] = 0x47; p[1] = 0x41; p[2] = 0x00; p[3] = 0x10   // pid 0x100, payload start
        p[4] = 0                                               // pointer
        val entries = types.size * 5
        val length = 9 + entries + 4
        val s = byteArrayOf(2, (0xb0 or (length shr 8)).toByte(), (length and 0xff).toByte(), 0, 1, 0xc1.toByte(), 0, 0, 0xe1.toByte(), 1, 0xf0.toByte(), 0)
        s.copyInto(p, 5)
        var at = 5 + s.size
        types.forEachIndexed { n, t -> p[at] = t.toByte(); p[at + 1] = (0xe1).toByte(); p[at + 2] = (0x01 + n).toByte(); p[at + 3] = 0xf0.toByte(); p[at + 4] = 0; at += 5 }
        return p
    }

    private fun pat(): ByteArray {
        val p = ByteArray(188) { 0xff.toByte() }
        p[0] = 0x47; p[1] = 0x40; p[2] = 0; p[3] = 0x10; p[4] = 0
        byteArrayOf(0x00, 0xb0.toByte(), 13, 0, 1, 0xc1.toByte(), 0, 0, 0, 1, 0xe1.toByte(), 0, 0, 0, 0).copyInto(p, 5)
        return p
    }

    @Test fun namesTheStreamsInThePmt() {
        val s = TsSegmenter(now = { 0 })
        s.push(pat() + pmtPacket(0x1b, 0x0f))
        assertEquals("H.264, AAC", s.streams)
    }

    @Test fun namesAnH265AndAnUnknownType() {
        val s = TsSegmenter(now = { 0 })
        s.push(pat() + pmtPacket(0x24, 0x99))
        assertEquals("H.265, type 0x99", s.streams)
    }

    @Test fun emptyBeforeThePmt() = assertEquals("", TsSegmenter(now = { 0 }).streams)
}

/** Blink's indoor cameras send two AAC streams; the player stalls on them, so only one goes on (and none in the fallback). */
class TsSegmenterAudioTest {
    private fun packet(pid: Int, start: Boolean, fill: Int): ByteArray {
        val p = ByteArray(188) { fill.toByte() }
        p[0] = 0x47; p[1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte(); p[2] = (pid and 0xff).toByte(); p[3] = 0x10
        return p
    }

    private fun pat(): ByteArray {
        val p = ByteArray(188) { 0xff.toByte() }
        p[0] = 0x47; p[1] = 0x40; p[2] = 0; p[3] = 0x10; p[4] = 0
        byteArrayOf(0x00, 0xb0.toByte(), 13, 0, 1, 0xc1.toByte(), 0, 0, 0, 1, 0xe1.toByte(), 0, 0, 0, 0).copyInto(p, 5)
        return p
    }

    /** A PMT (pid 0x100, clock on the video pid 0x101) with H.264 on 0x101 and AAC on 0x102 and 0x103. */
    private fun pmt(): ByteArray {
        val p = ByteArray(188) { 0xff.toByte() }
        p[0] = 0x47; p[1] = 0x41; p[2] = 0; p[3] = 0x10; p[4] = 0
        val length = 9 + 15 + 4
        byteArrayOf(2, (0xb0 or (length shr 8)).toByte(), length.toByte(), 0, 1, 0xc1.toByte(), 0, 0, 0xe1.toByte(), 1, 0xf0.toByte(), 0).copyInto(p, 5)
        var at = 17
        for ((type, pid) in listOf(0x1b to 0x101, 0x0f to 0x102, 0x0f to 0x103)) {
            p[at] = type.toByte(); p[at + 1] = (0xe0 or (pid shr 8)).toByte(); p[at + 2] = (pid and 0xff).toByte(); p[at + 3] = 0xf0.toByte(); p[at + 4] = 0
            at += 5
        }
        return p
    }

    private fun run(keep: AudioKeep): ByteArray {
        val s = TsSegmenter(now = { 0 }, audio = keep)
        s.push(pat() + pmt() + packet(0x101, false, 0x11) + packet(0x102, false, 0x22) + packet(0x103, false, 0x33))
        return s.finish()!!.data
    }

    private fun pidsOf(data: ByteArray) = (0 until data.size / 188).map { ((data[it * 188 + 1].toInt() and 0x1f) shl 8) or (data[it * 188 + 2].toInt() and 0xff) }

    @Test fun keepsOneAudioStreamAndDropsTheOthersPackets() {
        val data = run(AudioKeep.One)
        assertEquals(listOf(0, 0x100, 0x101, 0x102), pidsOf(data))
    }

    @Test fun rewrittenPmtListsOnlyWhatIsKeptAndItsCrcHolds() {
        val s = TsSegmenter(now = { 0 })
        s.push(pat() + pmt() + packet(0x101, false, 0x11))
        assertEquals("H.264, AAC, AAC", s.streams)
        assertEquals("H.264, AAC", s.sent)
        val pmt = s.finish()!!.data.copyOfRange(188, 376)
        val length = ((pmt[6].toInt() and 0x0f) shl 8) or (pmt[7].toInt() and 0xff)
        assertEquals(9 + 10 + 4, length)
        // A section's CRC over the whole section including its own CRC comes to zero.
        var crc = -1
        for (n in 5 until 5 + 3 + length) {
            crc = crc xor ((pmt[n].toInt() and 0xff) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        assertEquals(0, crc)
    }

    @Test fun noAudioLeavesThePicture() = assertEquals(listOf(0, 0x100, 0x101), pidsOf(run(AudioKeep.None)))

    @Test fun allAudioPassesThroughUnchanged() = assertEquals(listOf(0, 0x100, 0x101, 0x102, 0x103), pidsOf(run(AudioKeep.All)))
}

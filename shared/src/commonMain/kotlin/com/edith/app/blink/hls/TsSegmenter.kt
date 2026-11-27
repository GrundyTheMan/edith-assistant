package com.edith.app.blink.hls

/** One piece of the live video: whole 188-byte MPEG-TS packets, [seconds] long by the wall clock, numbered from 0. */
class HlsSegment(val sequence: Long, val seconds: Double, val data: ByteArray)

/** Which of the camera's audio streams go on to the player (K4.17). */
enum class AudioKeep {
    /** All of them, as the camera sends. */
    All,
    /** Only the first one: a player that cannot choose between several unlabelled audio tracks stalls on a stream that has two. */
    One,
    /** None: the picture alone (the fallback when even one audio stream does not start). */
    None,
}

/**
 * Cuts the camera stream's MPEG-TS bytes into HLS segments (K4.17). Packets are 188 bytes starting with 0x47; a segment is cut at a PAT packet (which the camera
 * repeats about once a second, ahead of its key frames) once it is [minMs] long, or at [maxMs] whatever arrives, and each segment begins with the stream tables
 * (PAT and PMT) so a player can start from any of them. Lengths are measured by [now] (the wall clock), not read from the stream, so they are close, not exact.
 *
 * [audio] says which audio streams to pass on: when it leaves some out, the PMT is rewritten without them (with a new CRC) and their packets are dropped.
 * Blink's indoor cameras send H.264 with two AAC streams, and Apple's player never started on those.
 */
class TsSegmenter(
    private val now: () -> Long,
    private val minMs: Long = 2_000,
    private val maxMs: Long = 6_000,
    private val audio: AudioKeep = AudioKeep.One,
) {
    private var buffer = ByteArray(0)
    private val current = ArrayList<ByteArray>()
    private var startedAt = -1L
    private var next = 0L
    private var pat: ByteArray? = null
    private var pmt: ByteArray? = null
    private var pmtPid = -1
    private var dropped: Set<Int> = emptySet()

    /** What the camera's PMT lists ("H.264, AAC, AAC"), for the diagnostics line; empty until the PMT is seen. */
    @kotlin.concurrent.Volatile var streams: String = ""
        private set

    /** What goes on to the player after [audio] is applied ("H.264, AAC"); empty until the PMT is seen. */
    @kotlin.concurrent.Volatile var sent: String = ""
        private set

    /** Takes more bytes of the stream and returns the segments that are complete now. */
    fun push(bytes: ByteArray): List<HlsSegment> {
        buffer += bytes
        val out = ArrayList<HlsSegment>()
        var at = 0
        while (true) {
            // Find the next packet start: a 0x47 that is followed by another 0x47 one packet later (or is the last whole packet).
            while (at < buffer.size && buffer[at] != SYNC) at++
            if (buffer.size - at < PACKET) break
            if (buffer.size - at >= 2 * PACKET && buffer[at + PACKET] != SYNC) { at++; continue }
            val packet = buffer.copyOfRange(at, at + PACKET)
            at += PACKET
            accept(packet, out)
        }
        buffer = buffer.copyOfRange(at, buffer.size)
        return out
    }

    /** The segment under way, cut short, when the stream ends; null with nothing in it. */
    fun finish(): HlsSegment? = if (current.isEmpty()) null else cut()

    private fun accept(packet: ByteArray, out: MutableList<HlsSegment>) {
        val pid = ((packet[1].toInt() and 0x1f) shl 8) or (packet[2].toInt() and 0xff)
        if (pid in dropped) return
        val starts = packet[1].toInt() and 0x40 != 0
        val time = now()
        var packet = packet
        if (pid == 0 && starts) {
            pat = packet
            pmtPid = pmtPidOf(packet)
        } else if (pid == pmtPid && starts) {
            packet = rewritePmt(packet)
            pmt = packet
        }
        if (startedAt < 0) startedAt = time
        val age = time - startedAt
        if (current.isNotEmpty() && ((pid == 0 && starts && age >= minMs) || age >= maxMs)) out += cut()
        if (current.isEmpty()) {
            startedAt = time
            // A fresh segment opens with the stream tables: the PAT then the PMT (when this packet is the PAT, it is the first table itself).
            if (pid == 0 && starts) { current += packet; pmt?.let { current += it }; return }
            pat?.let { current += it }
            if (!(pid == pmtPid && starts)) pmt?.let { current += it }
        }
        current += packet
    }

    private fun cut(): HlsSegment {
        val data = ByteArray(current.size * PACKET)
        current.forEachIndexed { i, p -> p.copyInto(data, i * PACKET) }
        current.clear()
        val seconds = ((now() - startedAt).coerceAtLeast(1)) / 1000.0
        return HlsSegment(next++, seconds, data)
    }

    private class Es(val type: Int, val pid: Int, val bytes: ByteArray)

    /**
     * Reads the PMT packet: records what it lists, and when [audio] leaves streams out, returns a copy without them (and remembers their PIDs in [dropped] so their
     * packets are skipped). A PMT that does not parse, or that spans several packets, goes through unchanged.
     */
    private fun rewritePmt(packet: ByteArray): ByteArray {
        val adaptation = (packet[3].toInt() shr 4) and 3
        var i = 4
        if (adaptation == 2 || adaptation == 3) i += 1 + (packet[4].toInt() and 0xff)
        if (adaptation == 2 || i >= PACKET) return packet
        val s = i + 1 + (packet[i].toInt() and 0xff)          // table_id: after the pointer field
        if (s + 12 > PACKET) return packet
        val sectionLength = ((packet[s + 1].toInt() and 0x0f) shl 8) or (packet[s + 2].toInt() and 0xff)
        val programInfo = ((packet[s + 10].toInt() and 0x0f) shl 8) or (packet[s + 11].toInt() and 0xff)
        val entriesAt = s + 12 + programInfo
        val end = s + 3 + sectionLength - 4                    // minus the CRC
        if (end > PACKET || entriesAt > end) return packet
        val all = ArrayList<Es>()
        var at = entriesAt
        while (at + 5 <= end) {
            val length = 5 + (((packet[at + 3].toInt() and 0x0f) shl 8) or (packet[at + 4].toInt() and 0xff))
            if (at + length > end) return packet
            all += Es(packet[at].toInt() and 0xff, ((packet[at + 1].toInt() and 0x1f) shl 8) or (packet[at + 2].toInt() and 0xff), packet.copyOfRange(at, at + length))
            at += length
        }
        streams = all.joinToString(", ") { nameOf(it.type) }
        var audioSeen = 0
        val kept = all.filter { es ->
            if (!isAudio(es.type)) true
            else when (audio) {
                AudioKeep.All -> true
                AudioKeep.One -> (audioSeen++ == 0)
                AudioKeep.None -> false
            }
        }
        sent = kept.joinToString(", ") { nameOf(it.type) }
        dropped = (all - kept.toSet()).map { it.pid }.toSet()
        if (kept.size == all.size) return packet

        val body = kept.fold(ByteArray(0)) { acc, e -> acc + e.bytes }
        val newLength = 9 + programInfo + body.size + 4
        val out = ByteArray(PACKET) { 0xff.toByte() }
        packet.copyInto(out, 0, 0, entriesAt)                  // the header, the pointer, the table's start and its program descriptors
        out[s + 1] = ((packet[s + 1].toInt() and 0xf0) or (newLength shr 8)).toByte()
        out[s + 2] = (newLength and 0xff).toByte()
        // The clock's PID must stay one that is still in the stream: the first video one when its own stream was dropped.
        val pcr = ((packet[s + 8].toInt() and 0x1f) shl 8) or (packet[s + 9].toInt() and 0xff)
        if (pcr in dropped) kept.firstOrNull { !isAudio(it.type) }?.let {
            out[s + 8] = ((packet[s + 8].toInt() and 0xe0) or (it.pid shr 8)).toByte()
            out[s + 9] = (it.pid and 0xff).toByte()
        }
        body.copyInto(out, entriesAt)
        val crcAt = s + 3 + newLength - 4
        val crc = crc32(out, s, crcAt)
        out[crcAt] = (crc ushr 24).toByte(); out[crcAt + 1] = (crc ushr 16).toByte(); out[crcAt + 2] = (crc ushr 8).toByte(); out[crcAt + 3] = crc.toByte()
        return out
    }

    private fun isAudio(type: Int) = type == 0x03 || type == 0x04 || type == 0x0f || type == 0x11 || type == 0x81

    private fun nameOf(type: Int) = when (type) {
        0x01, 0x02 -> "MPEG video"; 0x1b -> "H.264"; 0x24 -> "H.265"
        0x03, 0x04 -> "MPEG audio"; 0x0f -> "AAC"; 0x11 -> "AAC-LATM"; 0x81 -> "AC-3"; 0x90 -> "G.711"; 0x06 -> "private data"
        else -> "type 0x" + type.toString(16)
    }

    /** The PMT's PID from a PAT packet: the first real program's, or -1. */
    private fun pmtPidOf(packet: ByteArray): Int {
        val adaptation = (packet[3].toInt() shr 4) and 3
        var i = 4
        if (adaptation == 2 || adaptation == 3) i += 1 + (packet[4].toInt() and 0xff)
        if (adaptation == 2 || i >= PACKET) return -1
        i += 1 + (packet[i].toInt() and 0xff)               // the pointer field
        if (i + 8 > PACKET) return -1
        val sectionLength = ((packet[i + 1].toInt() and 0x0f) shl 8) or (packet[i + 2].toInt() and 0xff)
        var entry = i + 8
        val end = minOf(i + 3 + sectionLength - 4, PACKET)   // minus the CRC
        while (entry + 4 <= end) {
            val program = ((packet[entry].toInt() and 0xff) shl 8) or (packet[entry + 1].toInt() and 0xff)
            val pid = ((packet[entry + 2].toInt() and 0x1f) shl 8) or (packet[entry + 3].toInt() and 0xff)
            if (program != 0) return pid
            entry += 4
        }
        return -1
    }

    private companion object {
        const val PACKET = 188
        const val SYNC: Byte = 0x47

        /** The CRC-32 an MPEG section ends with (polynomial 0x04C11DB7, start 0xFFFFFFFF, no reflection, no final xor) over bytes [from] until [until]. */
        fun crc32(data: ByteArray, from: Int, until: Int): Int {
            var crc = -1
            for (n in from until until) {
                crc = crc xor ((data[n].toInt() and 0xff) shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }
    }
}

/** The live playlist for the segments kept: the last [window] of them, the newest at the end; [end] adds the end marker. */
class HlsPlaylist(private val window: Int = 4) {
    private val segments = ArrayList<HlsSegment>()
    var ended = false
        private set

    fun add(segment: HlsSegment) {
        segments += segment
        while (segments.size > window) segments.removeAt(0)
    }

    fun end() { ended = true }

    fun segment(sequence: Long): HlsSegment? = segments.firstOrNull { it.sequence == sequence }
    val isEmpty get() = segments.isEmpty()

    fun render(): String = buildString {
        val target = maxOf(1, segments.maxOfOrNull { kotlin.math.ceil(it.seconds).toInt() } ?: 1)
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:").append(target).append('\n')
        append("#EXT-X-MEDIA-SEQUENCE:").append(segments.firstOrNull()?.sequence ?: 0).append('\n')
        for (s in segments) {
            val millis = (s.seconds * 1000).toLong()
            append("#EXTINF:").append(millis / 1000).append('.').append((millis % 1000).toString().padStart(3, '0')).append(",\n")
            append("seg").append(s.sequence).append(".ts\n")
        }
        if (ended) append("#EXT-X-ENDLIST\n")
    }
}

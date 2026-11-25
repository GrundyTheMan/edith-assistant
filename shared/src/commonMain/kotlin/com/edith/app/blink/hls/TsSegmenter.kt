package com.edith.app.blink.hls

/** One piece of the live video: whole 188-byte MPEG-TS packets, [seconds] long by the wall clock, numbered from 0. */
class HlsSegment(val sequence: Long, val seconds: Double, val data: ByteArray)

/**
 * Cuts the camera stream's MPEG-TS bytes into HLS segments (K4.17). Packets are 188 bytes starting with 0x47; a segment is cut at a PAT packet (which the camera
 * repeats about once a second, ahead of its key frames) once it is [minMs] long, or at [maxMs] whatever arrives, and each segment begins with the stream tables
 * (PAT and PMT) so a player can start from any of them. Lengths are measured by [now] (the wall clock), not read from the stream, so they are close, not exact.
 */
class TsSegmenter(
    private val now: () -> Long,
    private val minMs: Long = 2_000,
    private val maxMs: Long = 6_000,
) {
    private var buffer = ByteArray(0)
    private val current = ArrayList<ByteArray>()
    private var startedAt = -1L
    private var next = 0L
    private var pat: ByteArray? = null
    private var pmt: ByteArray? = null
    private var pmtPid = -1

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
        val starts = packet[1].toInt() and 0x40 != 0
        val time = now()
        if (pid == 0 && starts) {
            pat = packet
            pmtPid = pmtPidOf(packet)
        } else if (pid == pmtPid && starts) pmt = packet
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

package com.edith.app.blink

import io.ktor.http.Url

/**
 * Blink's live view (C32; Jarvis's `blinkLive.ts`, blinkpy's `livestream.py`): the camera's video comes from Blink's
 * "immis" server over TLS, in a framing of its own around MPEG transport stream packets. This is the protocol only —
 * the address, the opening bytes, the keep-alives and the reader that picks the video out. [BlinkLiveStream] runs it.
 */
object BlinkLive {
    /** Where the stream is, from the `immis://` address Blink's liveview answer gives. */
    data class Immis(val host: String, val port: Int, val connectionId: String, val clientId: Long)

    fun parseImmis(server: String): Immis? {
        // Ktor fills in a host for an address with none ("https://"): no host in the text means no stream.
        if (server.substringAfter("//", "").substringBefore('/').substringBefore('?').isEmpty()) return null
        val url = runCatching { Url(server.replaceFirst(Regex("^immis:"), "https:")) }.getOrNull() ?: return null
        val host = url.host.takeIf { it.isNotEmpty() } ?: return null
        val last = url.encodedPath.split('/').lastOrNull { it.isNotEmpty() }.orEmpty()
        val client = url.encodedQuery.split('&').firstOrNull { it.startsWith("client_id=") }?.substringAfter('=')?.toLongOrNull() ?: 0L
        // An address with no port says 443; Ktor reports the scheme's own port then, which here is 443 too.
        return Immis(host, url.specifiedPort.takeIf { it > 0 } ?: 443, last.substringBefore("__"), client)
    }

    /** A length-prefixed field padded with zero bytes to [size]. */
    private fun field(value: String, size: Int): ByteArray {
        val out = ByteArray(4 + size)
        out[0] = (size ushr 24).toByte(); out[1] = (size ushr 16).toByte(); out[2] = (size ushr 8).toByte(); out[3] = size.toByte()
        value.encodeToByteArray().copyInto(out, 4, 0, minOf(size, value.encodeToByteArray().size))
        return out
    }

    private fun int32(v: Long): ByteArray = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** The 122 bytes that open the stream: the camera's serial, the client and the connection. */
    fun handshake(serial: String, server: Immis): ByteArray =
        byteArrayOf(0x00, 0x00, 0x00, 0x28) +
            field(serial, 16) +
            int32(server.clientId) +
            byteArrayOf(0x01, 0x08) +
            field("", 64) +   // where a token would go; Blink accepts it empty
            field(server.connectionId, 16) +
            byteArrayOf(0x00, 0x00, 0x00, 0x01)

    private fun header(type: Int, sequence: Long, length: Int): ByteArray = byteArrayOf(type.toByte()) + int32(sequence) + int32(length.toLong())

    /** Sent every 10 seconds so the server keeps the stream open. */
    fun keepAlive(sequence: Long): ByteArray = header(0x0a, sequence, 0)

    /** Sent every second, as the phone app does; all zeros. */
    fun latencyStats(): ByteArray = header(0x12, 1000, 24) + ByteArray(24)

    /**
     * Splits what the server sends into its 9-byte-header messages and returns the video: payloads of type 0 that start
     * with an MPEG-TS sync byte (0x47). Keeps a partial message for the next call.
     */
    class FrameReader {
        private var pending = ByteArray(0)

        fun push(data: ByteArray, length: Int = data.size): List<ByteArray> {
            pending += data.copyOf(length)
            val video = mutableListOf<ByteArray>()
            var at = 0
            while (pending.size - at >= 9) {
                val type = pending[at].toInt() and 0xff
                val len = ((pending[at + 5].toLong() and 0xff) shl 24 or ((pending[at + 6].toLong() and 0xff) shl 16) or
                    ((pending[at + 7].toLong() and 0xff) shl 8) or (pending[at + 8].toLong() and 0xff))
                if (len > MAX_MESSAGE) { pending = ByteArray(0); return video } // not Blink's framing: drop it rather than wait forever
                if (pending.size - at < 9 + len) break
                val start = at + 9
                if (type == 0 && len > 0 && pending[start].toInt() == 0x47) video += pending.copyOfRange(start, start + len.toInt())
                at = start + len.toInt()
            }
            pending = pending.copyOfRange(at, pending.size)
            return video
        }
    }

    private const val MAX_MESSAGE = 4L * 1024 * 1024
}

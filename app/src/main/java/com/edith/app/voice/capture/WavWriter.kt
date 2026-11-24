package com.edith.app.voice.capture

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A finished recording: a 16 kHz mono 16-bit PCM WAV (the format Ollama accepts, L12). */


/**
 * Streams 16-bit PCM samples into a WAV file (C12 §2): a 44-byte header with placeholder sizes first, patched with
 * the real sizes by [close]. Not thread-safe: one writer thread.
 */
class WavWriter(private val file: File, private val sampleRate: Int = SAMPLE_RATE) : Closeable {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0); write(header(sampleRate, 0)) }
    private val buffer = ByteBuffer.allocate(8_192 * 2).order(ByteOrder.LITTLE_ENDIAN)
    var dataBytes = 0L
        private set

    fun write(samples: ShortArray, count: Int) {
        var i = 0
        while (i < count) {
            buffer.clear()
            val n = minOf(count - i, buffer.capacity() / 2)
            for (k in 0 until n) buffer.putShort(samples[i + k])
            out.write(buffer.array(), 0, n * 2)
            dataBytes += n * 2
            i += n
        }
    }

    val durationMs: Long get() = dataBytes * 1_000 / (sampleRate * BYTES_PER_SAMPLE)

    override fun close() {
        out.seek(0)
        out.write(header(sampleRate, dataBytes))
        out.close()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BYTES_PER_SAMPLE = 2
        const val HEADER_BYTES = 44

        /** The canonical RIFF/WAVE header for mono 16-bit PCM with [dataBytes] of samples. */
        fun header(sampleRate: Int, dataBytes: Long): ByteArray = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1)                                          // PCM
            putShort(1)                                          // mono
            putInt(sampleRate)
            putInt(sampleRate * BYTES_PER_SAMPLE)                // byte rate
            putShort(BYTES_PER_SAMPLE.toShort())                 // block align
            putShort(16)                                         // bits per sample
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes.toInt())
        }.array()
    }
}

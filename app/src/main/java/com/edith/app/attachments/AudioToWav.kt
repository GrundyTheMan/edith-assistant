package com.edith.app.attachments

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.edith.app.voice.capture.WavWriter
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes any audio Android can play (M4A, AAC, OGG, 3GP, FLAC…) to 16-bit PCM, downmixed to mono, as a WAV at the
 * source's sample rate (C15 §2; Ollama accepts only WAV and MP3, 03 §5). Blocking: call it off the main thread.
 */
object AudioToWav {
    private const val TIMEOUT_US = 10_000L
    /** 5 s of no output after the last input. */
    private const val MAX_IDLE_AFTER_INPUT = 500

    fun convert(source: FileDescriptor, target: File) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("No audio track")
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var writer: WavWriter? = null
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idleAfterInput = 0   // a decoder that never signals the end mustn't loop forever
            try {
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (i >= 0) {
                            val size = extractor.readSampleData(codec.getInputBuffer(i) ?: throw IOException("The decoder gave no input buffer"), 0)
                            if (size < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone && ++idleAfterInput > MAX_IDLE_AFTER_INPUT) break
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val out = codec.outputFormat
                            sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            floatPcm = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        o >= 0 -> {
                            if (info.size > 0) {
                                val buffer = (codec.getOutputBuffer(o) ?: throw IOException("The decoder gave no output buffer")).order(ByteOrder.LITTLE_ENDIAN)
                                buffer.position(info.offset).limit(info.offset + info.size)
                                val mono = downmix(buffer, channels, floatPcm)
                                val w = writer ?: WavWriter(target, sampleRate).also { writer = it }
                                w.write(mono, mono.size)
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            } finally {
                (writer ?: WavWriter(target, sampleRate)).close()   // an empty but valid WAV if nothing decoded
            }
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }

    /** Interleaved PCM (16-bit or float) → mono 16-bit, averaging the channels. */
    private fun downmix(buffer: java.nio.ByteBuffer, channels: Int, floatPcm: Boolean): ShortArray {
        val ch = channels.coerceAtLeast(1)
        return if (floatPcm) {
            val f = buffer.asFloatBuffer()
            ShortArray(f.remaining() / ch) { i ->
                var sum = 0f
                for (c in 0 until ch) sum += f.get(i * ch + c)
                ((sum / ch).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
        } else {
            val s = buffer.asShortBuffer()
            ShortArray(s.remaining() / ch) { i ->
                var sum = 0
                for (c in 0 until ch) sum += s.get(i * ch + c)
                (sum / ch).toShort()
            }
        }
    }
}

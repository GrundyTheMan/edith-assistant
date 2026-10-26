package com.edith.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class AttachmentKind { IMAGE, AUDIO }

/**
 * A file attached to a chat message. Attachments exist only in memory while the app is running:
 * they're never written to the database or to storage.
 * [number] is how Edith and the user refer to it ("Attachment 3"); it's unique within the session.
 */
class Attachment(
    val number: Int,
    val kind: AttachmentKind,
    val fileName: String,
    val bytes: ByteArray
) {
    val label: String get() = "Attachment $number: $fileName (${kind.name.lowercase()})"
}

/** A file Edith wrote in a reply (a list, note, table or code). Also kept only in memory. */
data class WrittenFile(val name: String, val content: String)

/**
 * Reads picked images and audio files into memory, in forms Ollama accepts:
 * JPEG images no larger than [MAX_IMAGE_PX], and WAV or MP3 audio.
 */
class AttachmentReader(context: Context) {
    private val appContext = context.applicationContext

    suspend fun readImage(uri: Uri, number: Int): Attachment = withContext(Dispatchers.IO) {
        val bitmap = decodeScaled(uri)
        val jpeg = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        bitmap.recycle()
        Attachment(number, AttachmentKind.IMAGE, displayName(uri, "image.jpg"), jpeg)
    }

    suspend fun readAudio(uri: Uri, number: Int): Attachment = withContext(Dispatchers.IO) {
        val seconds = durationSeconds(uri)
        require(seconds <= MAX_AUDIO_SECONDS) {
            "That recording is ${seconds / 60} minutes long; audio attachments can be up to ${MAX_AUDIO_SECONDS / 60} minutes"
        }
        val name = displayName(uri, "audio")
        val mime = appContext.contentResolver.getType(uri) ?: ""
        val readsDirectly = mime in WAV_TYPES || mime in MP3_TYPES ||
            name.endsWith(".wav", ignoreCase = true) || name.endsWith(".mp3", ignoreCase = true)
        // Ollama reads WAV and MP3 directly; anything else is converted to WAV
        val bytes = if (readsDirectly) readBytes(uri) else convertToWav(uri)
        Attachment(number, AttachmentKind.AUDIO, name, bytes)
    }

    private fun decodeScaled(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder also applies the photo's EXIF rotation
            val source = ImageDecoder.createSource(appContext.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val scale = MAX_IMAGE_PX.toFloat() / maxOf(info.size.width, info.size.height)
                if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return appContext.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalArgumentException("That file isn't an image Edith can read")
    }

    private fun readBytes(uri: Uri): ByteArray =
        appContext.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Couldn't open that file" }.readBytes()
        }

    /** Decodes any audio format Android supports into a 16-bit mono WAV, in memory. */
    private fun convertToWav(uri: Uri): ByteArray {
        val extractor = MediaExtractor().apply { setDataSource(appContext, uri, null) }
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            throw IllegalArgumentException("That file has no audio Edith can read")
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val pcm = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    index >= 0 -> {
                        val chunk = ByteArray(info.size)
                        codec.getOutputBuffer(index)!!.apply { position(info.offset) }.get(chunk)
                        pcm.write(downmixToMono(chunk, channels))
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        return wavHeader(pcm.size(), sampleRate) + pcm.toByteArray()
    }

    private fun downmixToMono(pcm16: ByteArray, channels: Int): ByteArray {
        if (channels <= 1) return pcm16
        val input = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = input.remaining() / channels
        val output = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            var sum = 0
            repeat(channels) { sum += input.get() }
            output.putShort((sum / channels).toShort())
        }
        return output.array()
    }

    private fun wavHeader(dataSize: Int, sampleRate: Int): ByteArray =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)  // PCM, mono
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataSize)
        }.array()

    private fun durationSeconds(uri: Uri): Int = runCatching {
        MediaMetadataRetriever().run {
            setDataSource(appContext, uri)
            val millis = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            release()
            (millis / 1000).toInt()
        }
    }.getOrDefault(0)

    private fun displayName(uri: Uri, fallback: String): String =
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: fallback

    private companion object {
        const val MAX_IMAGE_PX = 1536
        const val MAX_AUDIO_SECONDS = 5 * 60
        val WAV_TYPES = setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")
        val MP3_TYPES = setOf("audio/mpeg", "audio/mp3")
    }
}

/** Lets MediaPlayer play audio straight from memory, so attachments never touch storage. */
class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val count = minOf(size, data.size - position.toInt())
        System.arraycopy(data, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {}
}

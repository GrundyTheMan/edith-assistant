package com.edith.app.attachments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.data.db.AttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * C15 §2 on the device, where the real image and audio decoders are: scaling, EXIF rotation, WAV/MP3 kept as they
 * are, M4A converted to mono WAV with its length kept, and audio over 5 minutes refused. The audio fixtures in
 * `androidTest/assets/attachments` are a 12 s spoken note (Windows text-to-speech, converted with ffmpeg).
 */
@RunWith(AndroidJUnit4::class)
class AttachmentIntakeDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(app.cacheDir, "intake-test").apply { deleteRecursively(); mkdirs() }
    private val pending = File(work, "pending")
    private val intake = AttachmentIntake(app.contentResolver, pending, AppDispatchers(Dispatchers.Main, Dispatchers.IO, Dispatchers.Default))

    @After fun tearDown() {
        work.deleteRecursively()
    }

    @Test fun aLargePhotoIsScaledTo1536AndSavedAsJpeg() = runBlocking {
        val photo = jpeg(4000, 3000)
        val result = intake.image(Uri.fromFile(photo)) as AttachmentIntake.Result.Ready
        val size = bounds(result.attachment.file)
        assertEquals(1536 to 1152, size)
        assertEquals(AttachmentKind.Image, result.attachment.kind)
        assertEquals("image/jpeg", result.attachment.mimeType)
        assertTrue(result.attachment.file.parentFile == pending)
    }

    @Test fun anExifRotatedPhotoComesOutUpright() = runBlocking {
        val photo = jpeg(2000, 1000)   // stored landscape, but the camera says "rotate 90°"
        ExifInterface(photo.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val result = intake.image(Uri.fromFile(photo)) as AttachmentIntake.Result.Ready
        assertEquals("portrait, and scaled to 1536", 768 to 1536, bounds(result.attachment.file))
    }

    @Test fun notAnImageIsRefused() = runBlocking {
        val text = File(work, "notes.jpg").apply { writeText("not really a picture") }
        assertTrue(intake.image(Uri.fromFile(text)) is AttachmentIntake.Result.Refused)
    }

    @Test fun wavAndMp3AreKeptAsTheyAre() = runBlocking {
        for ((asset, mime) in listOf("note.wav" to "audio/wav", "note.mp3" to "audio/mpeg")) {
            val source = asset(asset)
            val result = intake.audio(Uri.fromFile(source)) as AttachmentIntake.Result.Ready
            assertEquals(asset, mime, result.attachment.mimeType)
            assertTrue(asset, result.attachment.file.readBytes().contentEquals(source.readBytes()))
            assertTrue("$asset ${result.attachment.durationMs}", abs(result.attachment.durationMs!! - 12_070) < 150)
        }
    }

    @Test fun m4aIsConvertedToMonoWavWithItsLengthKept() = runBlocking {
        val result = intake.audio(Uri.fromFile(asset("note.m4a"))) as AttachmentIntake.Result.Ready
        val a = result.attachment
        assertEquals("audio/wav", a.mimeType)
        assertTrue(a.fileName.endsWith(".wav"))
        val bytes = a.file.readBytes()
        val h = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4)); assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals("mono", 1, h.getShort(22).toInt())
        assertEquals(16, h.getShort(34).toInt())
        val rate = h.getInt(24)
        assertEquals(44_100, rate)
        val dataBytes = h.getInt(40)
        assertEquals(bytes.size - 44, dataBytes)
        val ms = dataBytes / 2 * 1000L / rate
        assertTrue("decoded $ms ms", abs(ms - 12_070) <= 50 + 46)   // AAC adds up to ~1 frame (23 ms) at each end
        // Not silence: the decoder really produced the speech.
        val peak = (44 until bytes.size step 2).maxOf { abs(h.getShort(it).toInt()) }
        assertTrue("peak $peak", peak > 1000)
    }

    @Test fun audioOverFiveMinutesIsRefusedWithTheReason() = runBlocking {
        val result = intake.audio(Uri.fromFile(asset("long.m4a"))) as AttachmentIntake.Result.Refused
        assertTrue(result.message, result.message.contains("5:0") && result.message.contains("up to 5 minutes"))
        assertTrue("nothing left behind", pending.listFiles().isNullOrEmpty())
    }

    @Test fun notAudioIsRefused() = runBlocking {
        val photo = jpeg(100, 100)
        assertTrue(intake.audio(Uri.fromFile(photo)) is AttachmentIntake.Result.Refused)
    }

    private fun jpeg(w: Int, h: Int): File {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 120, 40)) }
        return File(work, "photo-${w}x$h.jpg").also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
    }

    private fun bounds(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return o.outWidth to o.outHeight
    }

    private fun asset(name: String): File = File(work, name).also { f ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("attachments/$name").use { input ->
            f.outputStream().use { input.copyTo(it) }
        }
    }
}

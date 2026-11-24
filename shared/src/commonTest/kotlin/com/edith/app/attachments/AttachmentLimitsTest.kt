package com.edith.app.attachments

import com.edith.app.attachments.AttachmentLimits.withExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** K2.10: the intake rules every platform follows, now plain shared code that runs on the desktop, Android and the iPhone simulator. */
class AttachmentLimitsTest {
    @Test fun photosAreScaledToTheLimitOnTheLongestSideAndNeverUp() {
        assertEquals(1536 to 1152, AttachmentLimits.scaled(4000, 3000))
        assertEquals(1152 to 1536, AttachmentLimits.scaled(3000, 4000))
        assertEquals(800 to 600, AttachmentLimits.scaled(800, 600))
        assertEquals(1536 to 1536, AttachmentLimits.scaled(1536, 1536))
    }

    @Test fun aVeryThinPhotoKeepsAtLeastOnePixel() {
        assertEquals(1536 to 1, AttachmentLimits.scaled(100_000, 10))
    }

    @Test fun extensionsAreReplacedOrAdded() {
        assertEquals("receipt.jpg", "receipt.png".withExtension("jpg"))
        assertEquals("voice note.wav", "voice note.m4a".withExtension("wav"))
        assertEquals("photo.jpg", "photo".withExtension("jpg"))
    }

    @Test fun wavAndMp3AreKeptAndEverythingElseIsConverted() {
        assertEquals("wav", AttachmentLimits.audioPassthrough("audio/x-wav", "a.bin"))
        assertEquals("mp3", AttachmentLimits.audioPassthrough("audio/mpeg", "a.bin"))
        assertNull(AttachmentLimits.audioPassthrough("audio/mp4", "note.m4a"))
        assertNull(AttachmentLimits.audioPassthrough("audio/ogg", "note.wav"))   // a known type wins over the name
    }

    @Test fun anUnknownTypeFallsBackToTheFileName() {
        assertEquals("wav", AttachmentLimits.audioPassthrough("", "Note.WAV"))
        assertEquals("mp3", AttachmentLimits.audioPassthrough("", "song.mp3"))
        assertNull(AttachmentLimits.audioPassthrough("", "note.m4a"))
    }

    @Test fun lengthsReadAsMinutesAndSeconds() {
        assertEquals("1:05", AttachmentLimits.minutes(65_000))
        assertEquals("5:00", AttachmentLimits.minutes(AttachmentLimits.MAX_AUDIO_MS))
        assertEquals("0:09", AttachmentLimits.minutes(9_999))
    }
}

package com.edith.app.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test

/** C36: `speech_voice` is stored as the words the key registry names, and an unknown word means Automatic. */
class SpeechVoiceSettingTest {
    @Test fun `the voice is stored as auto, phone or own and an unknown value means auto`() {
        val prefs = mutablePreferencesOf()
        SpeechVoice.entries.forEach { voice ->
            prefs.write(Settings(speechVoice = voice))
            assertEquals(voice.stored, prefs[SettingsKeys.SPEECH_VOICE])
            assertEquals(voice, prefs.toSettings().speechVoice)
        }
        assertEquals(listOf("auto", "phone", "own"), SpeechVoice.entries.map { it.stored })
        prefs[SettingsKeys.SPEECH_VOICE] = "robot"
        assertEquals(SpeechVoice.Auto, prefs.toSettings().speechVoice)
        assertEquals(SpeechVoice.Auto, Settings().speechVoice)
    }

    @Test fun `the banner dismissal is stored and is off by default`() {
        val prefs = mutablePreferencesOf()
        assertEquals(false, prefs.toSettings().voiceBannerDismissed)
        prefs.write(Settings(voiceBannerDismissed = true))
        assertEquals(true, prefs.toSettings().voiceBannerDismissed)
    }
}

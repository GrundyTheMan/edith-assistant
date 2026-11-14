package com.edith.app.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** DataStore against a temp file (C04 tests). */
class SettingsRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun closeStores() = scopes.forEach { it.cancel() }

    /**
     * Closes every open store and waits until each has let go of its file: only one DataStore may own a file at a
     * time, and cancelling alone returns before that (a second store opened at once failed on CI, not locally).
     */
    private suspend fun restart() {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        scopes.clear()
    }

    /** A fresh repository on [file]; a second one on the same file simulates the app being restarted. */
    private fun repository(file: File = File(temp.root, "settings.preferences_pb")): DataStoreSettingsRepository {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
    }

    @Test fun `empty store gives the defaults`() = runBlocking {
        assertEquals(Settings(), repository().current())
    }

    @Test fun `every key round-trips and survives a restart`() = runBlocking {
        val file = File(temp.root, "settings.preferences_pb")
        val changed = Settings(
            serverUrl = "http://192.168.4.82:11434", model = "gemma4:12b-it-q8_0", speakReplies = false,
            speechRate = 1.5f, units = Units.Imperial, searchProvider = "ollama",
            keepRecentAttachments = 0, handsFreeEnabled = true, wakeWordSensitivity = 0.8f, followUpSeconds = 12,
            currentConversationId = 7L, speechVoice = SpeechVoice.Own, voiceBannerDismissed = true,
        )
        repository(file).update { changed }
        restart()

        assertEquals(changed, repository(file).current())
    }

    // Tested on the preferences mapping rather than with two writes to one file: on Windows, DataStore can't
    // replace an existing file in a JVM test (Java's rename won't overwrite there). Android and Linux CI are fine.
    @Test fun `setting a nullable value back to null clears it`() {
        val prefs = mutablePreferencesOf()
        prefs.write(Settings(model = "m", currentConversationId = 3L))
        assertEquals("m", prefs.toSettings().model)

        prefs.write(Settings(model = null, currentConversationId = null))
        assertNull(prefs.toSettings().model)
        assertNull(prefs.toSettings().currentConversationId)
        assertNull("key removed, not stored as empty", prefs[SettingsKeys.MODEL])
    }

    @Test fun `units are stored in lower case as the key registry says`() = runBlocking {
        val file = File(temp.root, "settings.preferences_pb")
        repository(file).update { it.copy(units = Units.Metric) }
        restart()
        assertEquals(Units.Metric, repository(file).current().units)
        assertEquals(Units.Auto, Units.fromStored("nonsense"))
        assertEquals("metric", Units.Metric.stored)
    }
}

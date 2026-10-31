package com.edith.app.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
            speechRate = 1.5f, units = Units.Imperial, useDeviceLocation = true, searchProvider = "ollama",
            keepRecentAttachments = 0, handsFreeEnabled = true, wakeWordSensitivity = 0.8f, followUpSeconds = 12,
            googleAccountEmail = "owner@example.com",
        )
        repository(file).update { changed }
        scopes.forEach { it.cancel() }; scopes.clear()   // only one DataStore may own the file at a time

        assertEquals(changed, repository(file).current())
    }

    // Tested on the preferences mapping rather than with two writes to one file: on Windows, DataStore can't
    // replace an existing file in a JVM test (Java's rename won't overwrite there). Android and Linux CI are fine.
    @Test fun `setting a nullable value back to null clears it`() {
        val prefs = mutablePreferencesOf()
        prefs.write(Settings(model = "m", googleAccountEmail = "a@b.c"))
        assertEquals("m", prefs.toSettings().model)

        prefs.write(Settings(model = null, googleAccountEmail = null))
        assertNull(prefs.toSettings().model)
        assertNull(prefs.toSettings().googleAccountEmail)
        assertNull("key removed, not stored as empty", prefs[SettingsKeys.MODEL])
    }

    @Test fun `units are stored in lower case as the key registry says`() = runBlocking {
        val file = File(temp.root, "settings.preferences_pb")
        repository(file).update { it.copy(units = Units.Metric) }
        scopes.forEach { it.cancel() }; scopes.clear()
        assertEquals(Units.Metric, repository(file).current().units)
        assertEquals(Units.Auto, Units.fromStored("nonsense"))
        assertEquals("metric", Units.Metric.stored)
    }
}

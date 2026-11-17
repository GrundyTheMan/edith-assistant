package com.edith.app.alerts

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/** The owner's alert switches (Settings → Home → Alerts, C33). All off until switched on. */
interface AlertPrefs {
    val choices: Flow<AlertChoices>
    suspend fun set(backendId: String, kind: AlertKind, on: Boolean)
}

/** Kept in the settings DataStore. */
class DataStoreAlertPrefs(private val store: DataStore<Preferences>) : AlertPrefs {
    override val choices: Flow<AlertChoices> = store.data.map { AlertChoices(it[ON].orEmpty()) }

    override suspend fun set(backendId: String, kind: AlertKind, on: Boolean) {
        store.edit { p ->
            val key = AlertChoices.key(backendId, kind)
            p[ON] = if (on) p[ON].orEmpty() + key else p[ON].orEmpty() - key
        }
    }

    private companion object {
        val ON = stringSetPreferencesKey("alerts_on")
    }
}

/** For tests. */
class MemoryAlertPrefs(initial: AlertChoices = AlertChoices()) : AlertPrefs {
    private val state = MutableStateFlow(initial)
    override val choices: Flow<AlertChoices> = state.asStateFlow()

    override suspend fun set(backendId: String, kind: AlertKind, on: Boolean) {
        val key = AlertChoices.key(backendId, kind)
        state.value = AlertChoices(if (on) state.value.on + key else state.value.on - key)
    }
}

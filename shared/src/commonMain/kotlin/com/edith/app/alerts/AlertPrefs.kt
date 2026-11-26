package com.edith.app.alerts

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/** The owner's alert switches (Settings → Home → Alerts, C33). All off until switched on. */
interface AlertPrefs {
    val choices: Flow<AlertChoices>
    suspend fun set(backendId: String, kind: AlertKind, on: Boolean)

    /** When the app was last opened (ms since 1970), or null the first time: the start of While you were away (K4.16). The defaults make it optional for tests. */
    val lastOpened: Flow<Long?> get() = kotlinx.coroutines.flow.flowOf(null)
    suspend fun markOpened(atMs: Long) {}

    /** "Also when Edith is closed (through Jarvis)": the phone asks Jarvis to send Apple pushes (K6.2). Off until switched on. */
    val closedPush: Flow<Boolean> get() = kotlinx.coroutines.flow.flowOf(false)
    suspend fun setClosedPush(on: Boolean) {}
}

/** Kept in the settings DataStore. */
class DataStoreAlertPrefs(private val store: DataStore<Preferences>) : AlertPrefs {
    override val lastOpened: Flow<Long?> = store.data.map { it[OPENED] }
    override val closedPush: Flow<Boolean> = store.data.map { it[CLOSED_PUSH] == true }
    override suspend fun setClosedPush(on: Boolean) { store.edit { it[CLOSED_PUSH] = on } }

    override suspend fun markOpened(atMs: Long) {
        store.edit { it[OPENED] = atMs }
    }

    override val choices: Flow<AlertChoices> = store.data.map { AlertChoices(it[ON].orEmpty()) }

    override suspend fun set(backendId: String, kind: AlertKind, on: Boolean) {
        store.edit { p ->
            val key = AlertChoices.key(backendId, kind)
            p[ON] = if (on) p[ON].orEmpty() + key else p[ON].orEmpty() - key
        }
    }

    private companion object {
        val ON = stringSetPreferencesKey("alerts_on")
        val OPENED = longPreferencesKey("alerts_last_opened")
        val CLOSED_PUSH = androidx.datastore.preferences.core.booleanPreferencesKey("alerts_closed_push")
    }
}

/** For tests. */
class MemoryAlertPrefs(initial: AlertChoices = AlertChoices()) : AlertPrefs {
    private val opened = MutableStateFlow<Long?>(null)
    private val push = MutableStateFlow(false)
    override val closedPush: Flow<Boolean> = push.asStateFlow()
    override suspend fun setClosedPush(on: Boolean) { push.value = on }
    override val lastOpened: Flow<Long?> = opened.asStateFlow()
    override suspend fun markOpened(atMs: Long) { opened.value = atMs }

    private val state = MutableStateFlow(initial)
    override val choices: Flow<AlertChoices> = state.asStateFlow()

    override suspend fun set(backendId: String, kind: AlertKind, on: Boolean) {
        val key = AlertChoices.key(backendId, kind)
        state.value = AlertChoices(if (on) state.value.on + key else state.value.on - key)
    }
}

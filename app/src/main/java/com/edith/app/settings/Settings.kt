package com.edith.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Every setting Edith has (01-architecture §5.3). Defaults live here; later chunks only read/update them. */
data class Settings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val model: String? = null,
    val speakReplies: Boolean = true,
    val speechRate: Float = 1.0f,
    val units: Units = Units.Auto,
    val searchProvider: String = "",            // C10 sets the real default from decision D3
    val keepRecentAttachments: Int = 10,
    val handsFreeEnabled: Boolean = false,
    val wakeWordSensitivity: Float = 0.5f,
    val followUpSeconds: Int = 8,
    val googleAccountEmail: String? = null,
    /** C17b: how the owner last signed in to Google, `play` or `browser` (the chooser reads it after a restart). */
    val googleSignInMode: String? = null,
    val currentConversationId: Long? = null,   // C06b: the chat the app reopens on
) {
    companion object {
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:11434"
    }
}

/** Stored as `auto` / `metric` / `imperial` (01-architecture §5.3). */
enum class Units(val stored: String) {
    Auto("auto"), Metric("metric"), Imperial("imperial");

    companion object {
        fun fromStored(value: String?): Units = entries.firstOrNull { it.stored == value } ?: Auto
    }
}

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun current(): Settings
    suspend fun update(transform: (Settings) -> Settings)
}

/** The DataStore key registry (01-architecture §5.3) — names must not change: they're the storage format. */
internal object SettingsKeys {
    val SERVER_URL = stringPreferencesKey("server_url")
    val MODEL = stringPreferencesKey("model")
    val SPEAK_REPLIES = booleanPreferencesKey("speak_replies")
    val SPEECH_RATE = floatPreferencesKey("speech_rate")
    val UNITS = stringPreferencesKey("units")
    val SEARCH_PROVIDER = stringPreferencesKey("search_provider")
    val KEEP_RECENT_ATTACHMENTS = intPreferencesKey("keep_recent_attachments")
    val HANDS_FREE_ENABLED = booleanPreferencesKey("hands_free_enabled")
    val WAKE_WORD_SENSITIVITY = floatPreferencesKey("wake_word_sensitivity")
    val FOLLOW_UP_SECONDS = intPreferencesKey("follow_up_seconds")
    val GOOGLE_ACCOUNT_EMAIL = stringPreferencesKey("google_account_email")
    val GOOGLE_SIGN_IN_MODE = stringPreferencesKey("google_sign_in_mode")
    val CURRENT_CONVERSATION_ID = longPreferencesKey("current_conversation_id")
}

/** Settings in the Preferences DataStore `settings`. Create one per process (AppContainer does): see [create]. */
class DataStoreSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {

    override val settings: Flow<Settings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }   // unreadable file → defaults
        .map { it.toSettings() }

    override suspend fun current(): Settings = settings.first()

    /** Read, transform and write in one DataStore transaction, so concurrent updates can't overwrite each other. */
    override suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    companion object {
        fun create(context: Context): DataStoreSettingsRepository =
            DataStoreSettingsRepository(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") })
    }
}

internal fun Preferences.toSettings(): Settings {
    val d = Settings()
    return Settings(
        serverUrl = this[SettingsKeys.SERVER_URL] ?: d.serverUrl,
        model = this[SettingsKeys.MODEL],
        speakReplies = this[SettingsKeys.SPEAK_REPLIES] ?: d.speakReplies,
        speechRate = this[SettingsKeys.SPEECH_RATE] ?: d.speechRate,
        units = Units.fromStored(this[SettingsKeys.UNITS]),
        searchProvider = this[SettingsKeys.SEARCH_PROVIDER] ?: d.searchProvider,
        keepRecentAttachments = this[SettingsKeys.KEEP_RECENT_ATTACHMENTS] ?: d.keepRecentAttachments,
        handsFreeEnabled = this[SettingsKeys.HANDS_FREE_ENABLED] ?: d.handsFreeEnabled,
        wakeWordSensitivity = this[SettingsKeys.WAKE_WORD_SENSITIVITY] ?: d.wakeWordSensitivity,
        followUpSeconds = this[SettingsKeys.FOLLOW_UP_SECONDS] ?: d.followUpSeconds,
        googleAccountEmail = this[SettingsKeys.GOOGLE_ACCOUNT_EMAIL],
        googleSignInMode = this[SettingsKeys.GOOGLE_SIGN_IN_MODE],
        currentConversationId = this[SettingsKeys.CURRENT_CONVERSATION_ID],
    )
}

internal fun MutablePreferences.write(s: Settings) {
    this[SettingsKeys.SERVER_URL] = s.serverUrl
    setOrRemove(SettingsKeys.MODEL, s.model)
    this[SettingsKeys.SPEAK_REPLIES] = s.speakReplies
    this[SettingsKeys.SPEECH_RATE] = s.speechRate
    this[SettingsKeys.UNITS] = s.units.stored
    this[SettingsKeys.SEARCH_PROVIDER] = s.searchProvider
    this[SettingsKeys.KEEP_RECENT_ATTACHMENTS] = s.keepRecentAttachments
    this[SettingsKeys.HANDS_FREE_ENABLED] = s.handsFreeEnabled
    this[SettingsKeys.WAKE_WORD_SENSITIVITY] = s.wakeWordSensitivity
    this[SettingsKeys.FOLLOW_UP_SECONDS] = s.followUpSeconds
    setOrRemove(SettingsKeys.GOOGLE_ACCOUNT_EMAIL, s.googleAccountEmail)
    setOrRemove(SettingsKeys.GOOGLE_SIGN_IN_MODE, s.googleSignInMode)
    if (s.currentConversationId == null) remove(SettingsKeys.CURRENT_CONVERSATION_ID) else this[SettingsKeys.CURRENT_CONVERSATION_ID] = s.currentConversationId
}

/** A null value removes the key (DataStore can't store null). */
private fun MutablePreferences.setOrRemove(key: Preferences.Key<String>, value: String?) {
    if (value == null) remove(key) else this[key] = value
}

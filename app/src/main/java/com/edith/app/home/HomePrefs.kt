package com.edith.app.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** The owner's Home choices (C25 §5, §7): favourites and hidden devices by [HomeDevice.key], the Demo home switch. */
data class HomeChoices(
    val favorites: Set<String> = emptySet(),
    val hidden: Set<String> = emptySet(),
    /** Debug builds only: the fake backend is connected. */
    val demo: Boolean = false,
    /** The Home tab's last chip. */
    val chip: String? = null,
    /** Hidden devices' names, so Settings can list them while their service is away. */
    val hiddenNames: Map<String, String> = emptyMap(),
)

interface HomePrefs {
    val choices: Flow<HomeChoices>
    suspend fun setFavorite(key: String, on: Boolean)
    suspend fun setHidden(key: String, name: String, hidden: Boolean)
    suspend fun setDemo(on: Boolean)
    suspend fun setChip(chip: String)
}

class DataStoreHomePrefs(private val store: DataStore<Preferences>) : HomePrefs {
    override val choices: Flow<HomeChoices> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            val hidden = p[HIDDEN].orEmpty()
            HomeChoices(
                favorites = p[FAVORITES].orEmpty(),
                hidden = hidden.map { it.substringBefore(SEP) }.toSet(),
                demo = p[DEMO] ?: false,
                chip = p[CHIP],
                hiddenNames = hidden.associate { it.substringBefore(SEP) to it.substringAfter(SEP, it) },
            )
        }

    override suspend fun setFavorite(key: String, on: Boolean) {
        store.edit { p -> p[FAVORITES] = p[FAVORITES].orEmpty().let { if (on) it + key else it - key } }
    }

    override suspend fun setHidden(key: String, name: String, hidden: Boolean) {
        store.edit { p ->
            val rest = p[HIDDEN].orEmpty().filterNot { it.substringBefore(SEP) == key }.toSet()
            p[HIDDEN] = if (hidden) rest + "$key$SEP$name" else rest
        }
    }

    override suspend fun setDemo(on: Boolean) {
        store.edit { it[DEMO] = on }
    }

    override suspend fun setChip(chip: String) {
        store.edit { it[CHIP] = chip }
    }

    private companion object {
        val FAVORITES = stringSetPreferencesKey("home_favorites")
        /** "key\u0001name" per hidden device. */
        val HIDDEN = stringSetPreferencesKey("home_hidden")
        val DEMO = booleanPreferencesKey("home_demo")
        val CHIP = stringPreferencesKey("home_chip")
        const val SEP = '\u0001'
    }
}

/** For tests and previews. */
class MemoryHomePrefs(initial: HomeChoices = HomeChoices()) : HomePrefs {
    private val state = MutableStateFlow(initial)
    override val choices: Flow<HomeChoices> = state.asStateFlow()
    override suspend fun setFavorite(key: String, on: Boolean) {
        state.value = state.value.let { it.copy(favorites = if (on) it.favorites + key else it.favorites - key) }
    }
    override suspend fun setHidden(key: String, name: String, hidden: Boolean) {
        state.value = state.value.let {
            it.copy(
                hidden = if (hidden) it.hidden + key else it.hidden - key,
                hiddenNames = if (hidden) it.hiddenNames + (key to name) else it.hiddenNames - key,
            )
        }
    }
    override suspend fun setDemo(on: Boolean) { state.value = state.value.copy(demo = on) }
    override suspend fun setChip(chip: String) { state.value = state.value.copy(chip = chip) }
}

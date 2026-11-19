package com.edith.app.printer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/** The Printer page's cards, in the order the phone shows them (C39 part 8.3). The ids are what is remembered. */
internal object PrinterCards {
    const val STATUS = "status"
    const val CAMERA = "camera"
    const val TEMPERATURES = "temperatures"
    const val TOOLHEAD = "toolhead"
    const val FANS = "fans"
    const val LIMITS = "limits"
    const val SENSORS = "sensors"
    const val SLOTS = "slots"
    const val MACROS = "macros"
    const val FILES = "files"
    const val OBJECTS = "objects"
    const val QUEUE = "queue"
    const val RECORDINGS = "recordings"
    const val HISTORY = "history"
    const val CONSOLE = "console"
    const val MESH = "mesh"
    const val SYSTEM = "system"

    val ALL = listOf(STATUS, CAMERA, TEMPERATURES, TOOLHEAD, FANS, LIMITS, SENSORS, SLOTS, MACROS, FILES, OBJECTS, QUEUE, RECORDINGS, HISTORY, CONSOLE, MESH, SYSTEM)

    /** Open the first time: the rest start folded. */
    val EXPANDED_AT_FIRST = setOf(STATUS, CAMERA, TEMPERATURES)
    val COLLAPSED_AT_FIRST: Set<String> = ALL.toSet() - EXPANDED_AT_FIRST
}

/** What the owner chose on the Printer page and wants kept (kept in the settings DataStore). */
internal interface PrinterPrefs {
    /** The cards that are folded. Until the owner changes one, every card except Status, Camera and Temperatures. */
    val collapsed: Flow<Set<String>>
    suspend fun setCollapsed(card: String, collapsed: Boolean)
}

internal class DataStorePrinterPrefs(private val store: DataStore<Preferences>) : PrinterPrefs {
    override val collapsed: Flow<Set<String>> = store.data.map { it[COLLAPSED] ?: PrinterCards.COLLAPSED_AT_FIRST }

    override suspend fun setCollapsed(card: String, collapsed: Boolean) {
        store.edit { p ->
            val now = p[COLLAPSED] ?: PrinterCards.COLLAPSED_AT_FIRST
            p[COLLAPSED] = if (collapsed) now + card else now - card
        }
    }

    private companion object {
        val COLLAPSED = stringSetPreferencesKey("printer.collapsed")
    }
}

/** For tests. */
internal class MemoryPrinterPrefs(initial: Set<String> = PrinterCards.COLLAPSED_AT_FIRST) : PrinterPrefs {
    private val state = MutableStateFlow(initial)
    override val collapsed: Flow<Set<String>> = state.asStateFlow()
    override suspend fun setCollapsed(card: String, collapsed: Boolean) {
        state.value = if (collapsed) state.value + card else state.value - card
    }
}

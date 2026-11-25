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
object PrinterCards {
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
interface PrinterPrefs {
    /** The cards that are folded. Until the owner changes one, every card except Status, Camera and Temperatures. */
    val collapsed: Flow<Set<String>>
    suspend fun setCollapsed(card: String, collapsed: Boolean)

    /** The camera the owner last chose on the Camera card (null: the first one). */
    val camera: Flow<String?>
    suspend fun setCamera(id: String)

    /** Hide the printer's "// value:" console lines (on until switched off). */
    val consoleHideValues: Flow<Boolean>
    suspend fun setConsoleHideValues(on: Boolean)

    /** The lines the owner typed into the console, newest first, at most 50. */
    val consoleHistory: Flow<List<String>>
    suspend fun pushConsoleHistory(line: String)
}

class DataStorePrinterPrefs(private val store: DataStore<Preferences>) : PrinterPrefs {
    override val collapsed: Flow<Set<String>> = store.data.map { it[COLLAPSED] ?: PrinterCards.COLLAPSED_AT_FIRST }

    override suspend fun setCollapsed(card: String, collapsed: Boolean) {
        store.edit { p ->
            val now = p[COLLAPSED] ?: PrinterCards.COLLAPSED_AT_FIRST
            p[COLLAPSED] = if (collapsed) now + card else now - card
        }
    }

    override val camera: Flow<String?> = store.data.map { it[CAMERA] }
    override suspend fun setCamera(id: String) { store.edit { it[CAMERA] = id } }
    override val consoleHideValues: Flow<Boolean> = store.data.map { it[HIDE_VALUES] ?: true }
    override suspend fun setConsoleHideValues(on: Boolean) { store.edit { it[HIDE_VALUES] = on } }

    override val consoleHistory: Flow<List<String>> = store.data.map { lines(it[HISTORY]) }
    override suspend fun pushConsoleHistory(line: String) {
        store.edit { p -> p[HISTORY] = com.edith.app.printer.feed.ConsoleHistory.push(lines(p[HISTORY]), line).joinToString(LINE_BREAK.toString()) }
    }

    /** A saved history is one line of text per entry (a console line never holds a line break). */
    private fun lines(saved: String?): List<String> = saved?.split(LINE_BREAK)?.filter { it.isNotEmpty() }.orEmpty()

    private companion object {
        /** The character that separates saved lines: a line feed (character 10). */
        val LINE_BREAK: Char = 10.toChar()
        val HISTORY = androidx.datastore.preferences.core.stringPreferencesKey("printer.history")
        val COLLAPSED = stringSetPreferencesKey("printer.collapsed")
        val CAMERA = androidx.datastore.preferences.core.stringPreferencesKey("printer.camera")
        val HIDE_VALUES = androidx.datastore.preferences.core.booleanPreferencesKey("printer.consoleHideValues")
    }
}

/** For tests. */
class MemoryPrinterPrefs(initial: Set<String> = PrinterCards.COLLAPSED_AT_FIRST) : PrinterPrefs {
    private val state = MutableStateFlow(initial)
    override val collapsed: Flow<Set<String>> = state.asStateFlow()
    override suspend fun setCollapsed(card: String, collapsed: Boolean) {
        state.value = if (collapsed) state.value + card else state.value - card
    }
    private val cameraState = MutableStateFlow<String?>(null)
    override val camera: Flow<String?> = cameraState.asStateFlow()
    override suspend fun setCamera(id: String) { cameraState.value = id }
    private val hideState = MutableStateFlow(true)
    override val consoleHideValues: Flow<Boolean> = hideState.asStateFlow()
    override suspend fun setConsoleHideValues(on: Boolean) { hideState.value = on }
    private val historyState = MutableStateFlow<List<String>>(emptyList())
    override val consoleHistory: Flow<List<String>> = historyState.asStateFlow()
    override suspend fun pushConsoleHistory(line: String) { historyState.value = com.edith.app.printer.feed.ConsoleHistory.push(historyState.value, line) }
}

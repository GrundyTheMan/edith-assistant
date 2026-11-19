package com.edith.app.printer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edith.app.core.time.Clock
import com.edith.app.di.viewModelFactory
import com.edith.app.printer.PrinterCards
import com.edith.app.printer.PrinterPrefs
import com.edith.app.printer.feed.Connections
import com.edith.app.printer.feed.PrinterFormat
import com.edith.app.printer.feed.PrinterScreenState
import com.edith.app.printer.feed.PrinterSession
import com.edith.app.printer.feed.PrinterState
import com.edith.app.printer.feed.PrinterWords
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId

/** How strongly the connection chip should look: from "all well" to "something is wrong". */
internal enum class Tone { Good, Warn, Bad, Muted }

internal data class ConnectionChip(val text: String, val tone: Tone)

/** Everything the Printer page draws (C39 part 6.2 and 8.3). Built from the session's state, so it can be tested without a screen. */
internal data class PrinterUi(
    val screen: PrinterScreenState = PrinterScreenState.Connecting,
    /** The printer's name from Jarvis; "Printer" until Jarvis has said. */
    val name: String = "Printer",
    val collapsed: Set<String> = PrinterCards.COLLAPSED_AT_FIRST,
    /** The `alert.time` the owner dismissed; the same alert stays hidden until the page is closed. */
    val dismissedAlert: Long? = null,
    /** The phone's clock, in ms; moves every 30 s so the ages on the page stay right. */
    val now: Long = 0,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** The temperature presets Jarvis offers this phone (edited in Jarvis). */
    val presets: List<com.edith.app.printer.feed.Preset> = emptyList(),
) {
    /** The printer's state to draw: the live one, or the last one while Jarvis is away. Null when there is none. */
    val state: PrinterState? get() = when (val s = screen) {
        is PrinterScreenState.Live -> s.data.state
        is PrinterScreenState.JarvisAway -> s.last?.state
        else -> null
    }

    /** The server's answer, for each parameterless command, to whether it can be sent now (`pause`, `home:xyz`, ...). */
    val quick get() = (screen as? PrinterScreenState.Live)?.data?.quick.orEmpty()

    /** The page's cards are drawn: a state is available and a printer is set up. */
    val showsCards: Boolean get() = state?.let { it.connection != Connections.NONE } == true

    /** Drawn greyed with its controls off: Jarvis is away, or Jarvis cannot reach the printer. */
    val greyed: Boolean get() = screen is PrinterScreenState.JarvisAway || state?.connection == Connections.UNREACHABLE

    /** The words that sit in the pinned header, next to the name. */
    val chip: ConnectionChip get() {
        val st = state
        return when {
            screen is PrinterScreenState.JarvisAway ->
                ConnectionChip(if (screen.why == PrinterWords.UNAUTHORIZED) "Jarvis removed this phone" else "Jarvis not reachable", Tone.Bad)
            screen is PrinterScreenState.Connecting -> ConnectionChip("Connecting…", Tone.Muted)
            st == null -> ConnectionChip("", Tone.Muted)
            else -> when (st.connection) {
                Connections.READY -> ConnectionChip("Connected", Tone.Good)
                Connections.NONE -> ConnectionChip("No printer set up", Tone.Muted)
                Connections.CONNECTING -> ConnectionChip("Connecting…", Tone.Muted)
                Connections.KLIPPY_STARTUP -> ConnectionChip("Klipper is starting…", Tone.Warn)
                Connections.KLIPPY_SHUTDOWN -> ConnectionChip("Klipper: shutdown${st.message.dash()}", Tone.Bad)
                Connections.KLIPPY_ERROR -> ConnectionChip("Klipper: error${st.message.dash()}", Tone.Bad)
                Connections.UNREACHABLE -> ConnectionChip(
                    "Printer not answering" + (st.lastSeen?.let { " (last seen ${PrinterFormat.age(it, now, zone)})" } ?: ""), Tone.Bad,
                )
                else -> ConnectionChip(st.connection, Tone.Muted)
            }
        }
    }

    /** The line above the cards that says why they may be old or limited; null when all is well. */
    val banner: String? get() {
        val st = state
        return when (val s = screen) {
            is PrinterScreenState.JarvisAway -> s.why
            is PrinterScreenState.Live -> when (st?.connection) {
                Connections.UNREACHABLE ->
                    "Jarvis can't reach the printer (is it on?). Showing what it last saw" + (st.lastSeen?.let { " ${PrinterFormat.age(it, now, zone)}" } ?: "") + "."
                Connections.KLIPPY_STARTUP, Connections.KLIPPY_SHUTDOWN, Connections.KLIPPY_ERROR -> st.message?.takeIf { it.isNotBlank() } ?: chip.text
                else -> null
            }
            else -> null
        }
    }

    /** The full-page message when there are no cards to show (no Jarvis, not shared, no printer, waiting), else null. */
    val message: String? get() = when (screen) {
        PrinterScreenState.NoJarvis -> NO_JARVIS
        PrinterScreenState.NotShared -> NOT_SHARED
        PrinterScreenState.Connecting -> null
        else -> when {
            state?.connection == Connections.NONE -> NO_PRINTER
            state == null -> (screen as? PrinterScreenState.JarvisAway)?.why
            else -> null
        }
    }

    /** The printer's own latest error line, unless the owner dismissed that one. */
    val alert get() = state?.alert?.takeIf { it.time != dismissedAlert }

    companion object {
        const val NO_JARVIS = "Pair Edith with Jarvis to use the printer away from home."
        const val NOT_SHARED = "Jarvis isn't sharing the printer with this phone. In Jarvis, open Settings, find Share work data with Edith, and switch on Let paired phones see the printer."
        const val NO_PRINTER = "No printer is set up in Jarvis."
        private fun String?.dash() = if (this.isNullOrBlank()) "" else " – $this"
    }
}

/** What the page can ask of its ViewModel (kept apart so a UI test can drive the page without one). */
internal data class PrinterActions(
    val back: () -> Unit = {},
    val tryAgain: () -> Unit = {},
    val openPairing: () -> Unit = {},
    val toggleCard: (String) -> Unit = {},
    val dismissAlert: (Long) -> Unit = {},
    /** A card was opened or folded (or left the screen): the page tells the data layer, which loads lazily. */
    val cardVisible: (String, Boolean) -> Unit = { _, _ -> },
    val toggleSeries: (String) -> Unit = {},
    val moreHistory: () -> Unit = {},
    val setHideValues: (Boolean) -> Unit = {},
    val clearConsole: () -> Unit = {},
    val loadFiles: (Boolean) -> Unit = {},
    val setFileQuery: (String) -> Unit = {},
    val setPrintedOnly: (Boolean) -> Unit = {},
    val openFile: (String) -> Unit = {},
    val closeFile: () -> Unit = {},
    val chooseCamera: (String) -> Unit = {},
    // Stage 2: Controls and commands (the server decides; see PrinterControl).
    val setControls: (Boolean) -> Unit = {},
    val send: (com.edith.app.printer.feed.PrinterCommand) -> Unit = {},
    val confirm: () -> Unit = {},
    val dismissSheet: () -> Unit = {},
    val messageShown: () -> Unit = {},
    val setMacroQuery: (String) -> Unit = {},
    val setHiddenMacros: (List<String>) -> Unit = {},
    val rememberConsoleLine: (String) -> Unit = {},
)

/**
 * The Printer page's state (C39 part 6, 8). The page calls [attach] when it comes on screen and [detach] when it leaves: only then does
 * the session ask Jarvis anything.
 */
internal class PrinterViewModel(
    private val session: PrinterSession,
    private val prefs: PrinterPrefs,
    private val clock: Clock,
) : ViewModel() {
    private data class Local(val now: Long, val dismissedAlert: Long? = null)

    /** What the cards read beyond the live state: the chart, history, files, console and camera (lazy, see [PrinterCardData]). */
    val data = PrinterCardData(session, prefs, viewModelScope)
    val cards: StateFlow<CardsUi> = data.ui

    /** Controls, the confirm sheet and pending buttons (stage 2). */
    val control = PrinterControl(session, clock, viewModelScope)
    val controls: StateFlow<ControlUi> = control.ui

    private val local = MutableStateFlow(Local(clock.now().toEpochMilli()))
    private var ticking: Job? = null

    val ui: StateFlow<PrinterUi> = combine(session.state, session.shared, prefs.collapsed, local) { screen, shared, collapsed, l ->
        PrinterUi(screen, shared?.name?.takeIf { it.isNotBlank() } ?: "Printer", collapsed, l.dismissedAlert, l.now, clock.zone(), shared?.presets.orEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrinterUi(now = local.value.now, zone = clock.zone()))

    /** The page is on screen. */
    fun attach() {
        local.update { it.copy(now = clock.now().toEpochMilli(), dismissedAlert = null) }
        session.attach()
        data.attached()
        ticking?.cancel()
        ticking = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                local.update { it.copy(now = clock.now().toEpochMilli()) }
            }
        }
    }

    /** The page left the screen (or the app went to the background). */
    fun detach() {
        ticking?.cancel()
        ticking = null
        data.detached()
        control.detached()
        session.detach()
    }

    /** "Try again" when Jarvis could not be reached. */
    fun tryAgain() = session.attach()

    fun toggleCard(card: String) {
        viewModelScope.launch { prefs.setCollapsed(card, card !in ui.value.collapsed) }
    }

    fun dismissAlert(time: Long) = local.update { it.copy(dismissedAlert = time) }

    override fun onCleared() = session.detach()

    companion object {
        const val TICK_MS = 30_000L
        val Factory = viewModelFactory { c, _ -> PrinterViewModel(c.printerSession, c.printerPrefs, c.clock) }
    }
}

package com.edith.app.printer.ui

import com.edith.app.jarvis.FeedResult
import com.edith.app.printer.PrinterCards
import com.edith.app.printer.PrinterPrefs
import com.edith.app.printer.feed.ChartBuffer
import com.edith.app.printer.feed.ChartLine
import com.edith.app.printer.feed.ConsoleLine
import com.edith.app.printer.feed.ConsoleView
import com.edith.app.printer.feed.HistoryJob
import com.edith.app.printer.feed.HistoryTotals
import com.edith.app.printer.feed.PrinterFile
import com.edith.app.printer.feed.PrinterFileDetail
import com.edith.app.printer.feed.PrinterScreenState
import com.edith.app.printer.feed.PrinterSession
import com.edith.app.printer.feed.PrinterWords
import com.edith.app.printer.feed.Connections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class HistoryUi(
    val jobs: List<HistoryJob> = emptyList(),
    val totals: HistoryTotals? = null,
    val loading: Boolean = false,
    /** More jobs may be asked for (the last page was full). */
    val more: Boolean = false,
    val error: String? = null,
)

internal data class FilesUi(
    /** Null until the owner presses Load files: nothing is requested before that. */
    val files: List<PrinterFile>? = null,
    val cachedAt: Long = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val printedOnly: Boolean = true,
    val detailOf: String? = null,
    val detail: PrinterFileDetail? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
)

internal data class ConsoleUi(val lines: List<ConsoleLine> = emptyList(), val hideValues: Boolean = true, val error: String? = null)

/** The camera picture: [frame] is the newest JPEG or PNG; [number] changes with each picture so the page redraws even for equal bytes. */
internal class CameraUi(val cameraId: String? = null, val frame: ByteArray? = null, val number: Int = 0, val offline: Boolean = false)

internal data class CardsUi(
    val chart: List<ChartLine> = emptyList(),
    val chartHidden: Set<String> = emptySet(),
    val history: HistoryUi = HistoryUi(),
    val files: FilesUi = FilesUi(),
    val console: ConsoleUi = ConsoleUi(),
    val camera: CameraUi = CameraUi(),
)

/**
 * The data the Printer page's cards read from Jarvis beyond the live state (C39 8.4). Everything here is lazy and tied to what is
 * on screen: the chart is read when Temperatures opens, the history and the console when they open, the camera is asked for about once
 * a second only while its card is open, and **the file list is never asked for until the owner presses Load files** (it freezes the
 * printer for several seconds). [visible] is how the page says which cards are open.
 */
internal class PrinterCardData(
    private val session: PrinterSession,
    private val prefs: PrinterPrefs,
    private val scope: CoroutineScope,
) {
    private val _ui = MutableStateFlow(CardsUi())
    val ui: StateFlow<CardsUi> = _ui.asStateFlow()

    private val chart = ChartBuffer()
    private val open = HashSet<String>()
    private var lastRev = -1L
    private var lastEpoch = -1L
    private var consoleSeq = 0L
    private var chartSeeded = false
    private var historyLoaded = false
    private var consoleLoaded = false
    private var cameraJob: Job? = null
    private var consoleJob: Job? = null
    private var camera = CameraUi()

    init {
        scope.launch {
            session.state.collect { s ->
                if (s is PrinterScreenState.Live && !s.stale) onLive(s)
            }
        }
        scope.launch { prefs.consoleHideValues.collect { hide -> _ui.update { it.copy(console = it.console.copy(hideValues = hide)) } } }
    }

    /** The page left the screen: nothing is polled, and the next visit reads the chart again (it has a gap now). */
    fun detached() {
        cameraJob?.cancel(); cameraJob = null
        chartSeeded = false
        historyLoaded = false
        consoleLoaded = false
    }

    /** The page came back: the cards that are open load again. */
    fun attached() {
        for (id in open.toList()) if (cardNeedsLoad(id)) opened(id)
    }

    /** A card was opened or folded. */
    fun visible(card: String, isOpen: Boolean) {
        if (isOpen) { open.add(card); if (cardNeedsLoad(card)) opened(card) } else { open.remove(card); if (card == PrinterCards.CAMERA) stopCamera() }
    }

    private fun cardNeedsLoad(card: String) = when (card) {
        PrinterCards.TEMPERATURES -> !chartSeeded
        PrinterCards.HISTORY -> !historyLoaded
        PrinterCards.CONSOLE -> !consoleLoaded
        PrinterCards.CAMERA -> cameraJob?.isActive != true
        else -> false
    }

    private fun opened(card: String) {
        when (card) {
            PrinterCards.TEMPERATURES -> seedChart()
            PrinterCards.HISTORY -> loadHistory(start = 0)
            PrinterCards.CONSOLE -> loadConsole()
            PrinterCards.CAMERA -> startCamera()
        }
    }

    // --- The live answers ------------------------------------------------------------------------------------------------

    private fun onLive(s: PrinterScreenState.Live) {
        val a = s.data
        val fresh = a.epoch != lastEpoch || a.rev != lastRev
        lastRev = a.rev
        if (a.epoch != lastEpoch) { lastEpoch = a.epoch; consoleSeq = 0 }
        if (fresh) {
            chart.append(a.state)
            _ui.update { it.copy(chart = chart.lines()) }
        }
        if (PrinterCards.CONSOLE in open && consoleLoaded && a.consoleSeq > consoleSeq) fetchNewConsole()
    }

    // --- Temperatures ----------------------------------------------------------------------------------------------------

    private fun seedChart() {
        val routes = session.routes() ?: return
        scope.launch {
            when (val r = routes.temperatures()) {
                is FeedResult.Ok -> { chart.seed(r.value); chartSeeded = true; _ui.update { it.copy(chart = chart.lines()) } }
                else -> Unit   // the chart then builds up from the live answers; nothing to say
            }
        }
    }

    /** Taps on the legend: hide or show one line. */
    fun toggleSeries(id: String) = _ui.update { it.copy(chartHidden = if (id in it.chartHidden) it.chartHidden - id else it.chartHidden + id) }

    // --- History ---------------------------------------------------------------------------------------------------------

    private fun loadHistory(start: Int) {
        val routes = session.routes() ?: return
        if (_ui.value.history.loading) return
        _ui.update { it.copy(history = it.history.copy(loading = true, error = null)) }
        scope.launch {
            when (val r = routes.history(start)) {
                is FeedResult.Ok -> {
                    historyLoaded = true
                    _ui.update { u ->
                        val jobs = if (start == 0) r.value.jobs else u.history.jobs + r.value.jobs
                        u.copy(history = HistoryUi(jobs, r.value.totals, loading = false, more = r.value.jobs.size >= PAGE))
                    }
                }
                else -> _ui.update { it.copy(history = it.history.copy(loading = false, error = PrinterWords.forFailure(r))) }
            }
        }
    }

    /** "More": the next 50 jobs. */
    fun moreHistory() = loadHistory(_ui.value.history.jobs.size)

    // --- Console ---------------------------------------------------------------------------------------------------------

    private fun loadConsole() {
        val routes = session.routes() ?: return
        scope.launch {
            when (val r = routes.console(null, 200)) {
                is FeedResult.Ok -> { consoleLoaded = true; consoleSeq = r.value.seq; _ui.update { it.copy(console = it.console.copy(lines = r.value.lines, error = null)) } }
                else -> _ui.update { it.copy(console = it.console.copy(error = PrinterWords.forFailure(r))) }
            }
        }
    }

    /** Only the lines after the last one we have (Jarvis spec 15.6.1): no polling, no re-reading 200 lines. */
    private fun fetchNewConsole() {
        val routes = session.routes() ?: return
        if (consoleJob?.isActive == true) return
        val after = _ui.value.console.lines.lastOrNull()?.seq
        consoleJob = scope.launch {
            val r = routes.console(after, 200)
            if (r is FeedResult.Ok) {
                consoleSeq = r.value.seq
                _ui.update { it.copy(console = it.console.copy(lines = ConsoleView.append(it.console.lines, r.value.lines))) }
            }
        }
    }

    fun setHideValues(on: Boolean) { scope.launch { prefs.setConsoleHideValues(on) } }

    /** Clear empties the view only; the printer keeps its lines. */
    fun clearConsole() = _ui.update { it.copy(console = it.console.copy(lines = emptyList())) }

    // --- Files -----------------------------------------------------------------------------------------------------------

    /** The only thing that asks Jarvis for the list. [fresh] re-lists; the page does not offer it while printing. */
    fun loadFiles(fresh: Boolean) {
        val routes = session.routes() ?: return
        if (_ui.value.files.loading) return
        _ui.update { it.copy(files = it.files.copy(loading = true, error = null)) }
        scope.launch {
            when (val r = routes.files(fresh)) {
                is FeedResult.Ok -> _ui.update { it.copy(files = it.files.copy(files = r.value.files, cachedAt = r.value.cachedAt, loading = false)) }
                else -> _ui.update { it.copy(files = it.files.copy(loading = false, error = PrinterWords.forFailure(r))) }
            }
        }
    }

    fun setFileQuery(q: String) = _ui.update { it.copy(files = it.files.copy(query = q)) }
    fun setPrintedOnly(on: Boolean) = _ui.update { it.copy(files = it.files.copy(printedOnly = on)) }

    fun openFile(path: String) {
        val routes = session.routes() ?: return
        _ui.update { it.copy(files = it.files.copy(detailOf = path, detail = null, detailLoading = true, detailError = null)) }
        scope.launch {
            when (val r = routes.fileDetail(path)) {
                is FeedResult.Ok -> _ui.update { u -> if (u.files.detailOf == path) u.copy(files = u.files.copy(detail = r.value, detailLoading = false)) else u }
                else -> _ui.update { u -> if (u.files.detailOf == path) u.copy(files = u.files.copy(detailLoading = false, detailError = PrinterWords.forFailure(r))) else u } }
        }
    }

    fun closeFile() = _ui.update { it.copy(files = it.files.copy(detailOf = null, detail = null, detailLoading = false, detailError = null)) }

    // --- Camera ----------------------------------------------------------------------------------------------------------

    fun chooseCamera(id: String) {
        scope.launch { prefs.setCamera(id) }
        camera = CameraUi(cameraId = id)
        _ui.update { it.copy(camera = camera) }
        if (PrinterCards.CAMERA in open) { stopCamera(); startCamera() }
    }

    private fun stopCamera() { cameraJob?.cancel(); cameraJob = null }

    /**
     * About one picture a second while the Camera card is open and the page is on screen; two failures in a row say "Camera offline"
     * (and the loop goes on trying). Nothing is asked while the printer is not ready.
     */
    private fun startCamera() {
        cameraJob?.cancel()
        cameraJob = scope.launch {
            var failures = 0
            while (isActive) {
                val live = session.state.value as? PrinterScreenState.Live
                val routes = session.routes()
                val cameras = live?.data?.state?.cameras.orEmpty()
                val chosen = camera.cameraId?.takeIf { id -> cameras.any { it.id == id } } ?: prefs.cameraNow()?.takeIf { id -> cameras.any { it.id == id } } ?: cameras.firstOrNull()?.id
                if (live != null && routes != null && chosen != null && live.data.state.connection == Connections.READY) {
                    when (val r = routes.snapshot(chosen)) {
                        is FeedResult.Ok -> { failures = 0; camera = CameraUi(chosen, r.value, camera.number + 1, offline = false); _ui.update { it.copy(camera = camera) } }
                        else -> { failures++; if (failures >= 2) { camera = CameraUi(chosen, camera.frame, camera.number, offline = true); _ui.update { it.copy(camera = camera) } } }
                    }
                }
                delay(FRAME_MS)
            }
        }
    }

    private suspend fun PrinterPrefs.cameraNow(): String? = this.camera.firstOrNull()

    companion object {
        const val PAGE = 50
        const val FRAME_MS = 1_000L
    }
}

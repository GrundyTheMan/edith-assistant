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
import kotlinx.io.buffered
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class HistoryUi(
    val jobs: List<HistoryJob> = emptyList(),
    val totals: HistoryTotals? = null,
    val loading: Boolean = false,
    /** More jobs may be asked for (the last page was full). */
    val more: Boolean = false,
    val error: String? = null,
)

data class FilesUi(
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
    val upload: UploadUi = UploadUi(),
)

/**
 * A file being sent to the printer (stage 3). [name] is null when nothing was sent lately. While [active], [sent] of [total] bytes have gone;
 * afterwards either [done] (the name Jarvis saved it under) or [error] (in plain words) says how it ended. Cancelling clears all of it.
 */
data class UploadUi(
    val name: String? = null,
    val active: Boolean = false,
    val sent: Long = 0,
    val total: Long = 0,
    val done: String? = null,
    val error: String? = null,
    /** A file is chosen and waits for Send (Controls must be on then). */
    val ready: Boolean = false,
)

data class ConsoleUi(val lines: List<ConsoleLine> = emptyList(), val hideValues: Boolean = true, val error: String? = null, val history: List<String> = emptyList())

/** The Macros card: what Jarvis sent (null until read), and the filter. */
data class MacrosUi(val answer: com.edith.app.printer.feed.MacrosAnswer? = null, val loading: Boolean = false, val error: String? = null, val query: String = "")

/** The camera picture: [frame] is the newest JPEG or PNG; [number] changes with each picture so the page redraws even for equal bytes. */
class CameraUi(val cameraId: String? = null, val frame: ByteArray? = null, val number: Int = 0, val offline: Boolean = false)

/** The presets editor: saving, Jarvis's refusal in its words, and a count that goes up when a save worked (the dialog closes on it). */
data class PresetsUi(val saving: Boolean = false, val error: String? = null, val saved: Int = 0)

data class CardsUi(
    val chart: List<ChartLine> = emptyList(),
    val chartHidden: Set<String> = emptySet(),
    val history: HistoryUi = HistoryUi(),
    val files: FilesUi = FilesUi(),
    val console: ConsoleUi = ConsoleUi(),
    val camera: CameraUi = CameraUi(),
    val macros: MacrosUi = MacrosUi(),
    val presets: PresetsUi = PresetsUi(),
)

/**
 * The data the Printer page's cards read from Jarvis beyond the live state (C39 8.4). Everything here is lazy and tied to what is
 * on screen: the chart is read when Temperatures opens, the history and the console when they open, the camera is asked for about once
 * a second only while its card is open, and **the file list is never asked for until the owner presses Load files** (it freezes the
 * printer for several seconds). [visible] is how the page says which cards are open.
 */
class PrinterCardData(
    private val session: PrinterSession,
    private val prefs: PrinterPrefs,
    private val scope: CoroutineScope,
    /** Where a picked file is opened (a disk read); tests give their own. */
    private val io: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
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
    private var macrosLoaded = false
    private var cameraJob: Job? = null
    private var consoleJob: Job? = null
    private var uploadJob: Job? = null
    private var chosen: Chosen? = null
    private var camera = CameraUi()

    init {
        scope.launch {
            session.state.collect { s ->
                if (s is PrinterScreenState.Live && !s.stale) onLive(s)
            }
        }
        scope.launch { prefs.consoleHideValues.collect { hide -> _ui.update { it.copy(console = it.console.copy(hideValues = hide)) } } }
        scope.launch { prefs.consoleHistory.collect { h -> _ui.update { it.copy(console = it.console.copy(history = h)) } } }
    }

    /** The page left the screen: nothing is polled, and the next visit reads the chart again (it has a gap now). */
    fun detached() {
        // An upload cannot go on once the page is gone (the app may have no network in the background): it stops, and says so on return.
        if (uploadJob?.isActive == true) {
            val name = _ui.value.files.upload.name
            uploadJob?.cancel(); uploadJob = null
            chosen = null
            setUpload(UploadUi(name = name, error = "The upload stopped because the page was closed. Choose the file and send it again."))
        } else if (chosen != null) {
            // A chosen file is not kept past the page: Controls end with it, and the file's permission is only good while it is in use.
            chosen = null
            setUpload(UploadUi())
        }
        cameraJob?.cancel(); cameraJob = null
        chartSeeded = false
        historyLoaded = false
        consoleLoaded = false
        macrosLoaded = false
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
        PrinterCards.MACROS -> !macrosLoaded
        PrinterCards.CAMERA -> cameraJob?.isActive != true
        else -> false
    }

    private fun opened(card: String) {
        when (card) {
            PrinterCards.TEMPERATURES -> seedChart()
            PrinterCards.HISTORY -> loadHistory(start = 0)
            PrinterCards.CONSOLE -> loadConsole()
            PrinterCards.MACROS -> loadMacros()
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

    // --- Macros ---------------------------------------------------------------------------------------------------------

    private fun loadMacros() {
        val routes = session.routes() ?: return
        _ui.update { it.copy(macros = it.macros.copy(loading = true, error = null)) }
        scope.launch {
            when (val r = routes.macros()) {
                is FeedResult.Ok -> { macrosLoaded = true; _ui.update { it.copy(macros = it.macros.copy(answer = r.value, loading = false, error = null)) } }
                else -> _ui.update { it.copy(macros = it.macros.copy(loading = false, error = PrinterWords.forFailure(r))) }
            }
        }
    }

    fun setMacroQuery(q: String) = _ui.update { it.copy(macros = it.macros.copy(query = q)) }

    /** Hide or show macros on the phone and the PC (Jarvis saves the list; it needs Controls on, and refuses a name that is not a macro name). */
    fun setHiddenMacros(hidden: List<String>) {
        val routes = session.routes() ?: return
        scope.launch {
            when (val r = routes.settings(null, hidden)) {
                is FeedResult.Ok -> loadMacros()
                else -> _ui.update { it.copy(macros = it.macros.copy(error = PrinterWords.forFailure(r))) }
            }
        }
    }

    /** A console line the owner sent is kept for the up arrow. */
    fun rememberConsoleLine(line: String) { scope.launch { prefs.pushConsoleHistory(line) } }

    // --- Presets -------------------------------------------------------------------------------------------------------

    /** Saves the temperature presets in Jarvis (it needs Controls on and checks them again); the page then shows what Jarvis kept. */
    fun savePresets(presets: List<com.edith.app.printer.feed.Preset>) {
        val routes = session.routes() ?: return
        if (_ui.value.presets.saving) return
        _ui.update { it.copy(presets = it.presets.copy(saving = true, error = null)) }
        scope.launch {
            when (val r = routes.settings(presets, null)) {
                is FeedResult.Ok -> {
                    session.updatePresets(r.value.presets)
                    _ui.update { it.copy(presets = PresetsUi(saving = false, error = null, saved = it.presets.saved + 1)) }
                }
                else -> _ui.update { it.copy(presets = it.presets.copy(saving = false, error = PrinterWords.forFailure(r))) }
            }
        }
    }

    fun clearPresetsError() = _ui.update { it.copy(presets = it.presets.copy(error = null)) }

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

    /**
     * A command of the Files sheet was sent (Print, Add to queue or Delete): the sheet has done its job and closes, and a deleted file leaves
     * the list at once (the list itself is not asked for again: it keeps the printer busy for seconds). Called only when Jarvis accepted it,
     * so a refusal or a "No" on the confirmation leaves the sheet as it is.
     */
    fun commandSent(command: com.edith.app.printer.feed.PrinterCommand) {
        val path = when (command) {
            is com.edith.app.printer.feed.PrinterCommand.DeleteFile -> command.path
            is com.edith.app.printer.feed.PrinterCommand.Start -> command.path
            is com.edith.app.printer.feed.PrinterCommand.QueueAdd -> command.path
            else -> return
        }
        _ui.update { u ->
            val f = u.files
            val list = if (command is com.edith.app.printer.feed.PrinterCommand.DeleteFile) f.files?.filterNot { it.path == path } else f.files
            u.copy(files = f.copy(files = list, detailOf = null, detail = null, detailLoading = false, detailError = null))
        }
    }

    // --- Upload (stage 3) ---------------------------------------------------------------------------------------------------

    private class Chosen(val name: String, val size: Long, val open: () -> kotlinx.io.Source?)

    /**
     * The owner picked a file in the system picker. It is checked here with Jarvis's own rules (so a wrong file is told at once) and then
     * **waits**: nothing is sent until the owner presses Send ([sendUpload]). Two steps, not one, because the picker is another screen: it
     * takes the Printer page off the screen, and leaving the page switches Controls off on purpose (an emulator check showed a one-step
     * upload was always refused for that reason). Controls must be on when Send is pressed. [open] opens the file's stream later, once.
     */
    fun chooseUpload(name: String, size: Long, open: () -> kotlinx.io.Source?) {
        if (uploadJob?.isActive == true) return
        com.edith.app.printer.feed.PrinterUpload.problem(name, size)?.let { why -> chosen = null; setUpload(UploadUi(name = name, error = why)); return }
        chosen = Chosen(name, size, open)
        setUpload(UploadUi(name = name, total = size, ready = true))
    }

    /**
     * Sends the chosen file to the printer through Jarvis, streaming it (never held in memory) and showing the progress. One upload at a
     * time. Jarvis still decides: it needs Controls on, and its refusal is shown in its words (the file stays chosen, so the owner can turn
     * Controls on and press Send again).
     */
    fun sendUpload() {
        if (uploadJob?.isActive == true) return
        val file = chosen ?: return
        val routes = session.routes() ?: return
        setUpload(UploadUi(name = file.name, active = true, total = file.size))
        uploadJob = scope.launch {
            val stream = kotlinx.coroutines.withContext(io) { runCatching(file.open).getOrNull() }
            if (stream == null) { chosen = null; setUpload(UploadUi(name = file.name, error = com.edith.app.printer.feed.PrinterUpload.UNREADABLE_WORDS)); return@launch }
            val r = routes.upload(file.name, stream, file.size) { sent -> _ui.update { u -> u.copy(files = u.files.copy(upload = u.files.upload.copy(sent = sent))) } }
            when (r) {
                is FeedResult.Ok -> {
                    chosen = null
                    setUpload(UploadUi(name = file.name, sent = file.size, total = file.size, done = r.value.ifBlank { file.name }))
                    // A new file has not been printed before: the default "Printed before" filter would hide it from the list the owner is told to refresh.
                    _ui.update { it.copy(files = it.files.copy(printedOnly = false)) }
                }
                is FeedResult.Err -> if (r.code == "CONTROLS_OFF") setUpload(UploadUi(name = file.name, total = file.size, ready = true, error = PrinterWords.forFailure(r)))
                    else { chosen = null; setUpload(UploadUi(name = file.name, error = PrinterWords.forFailure(r))) }
                else -> { chosen = null; setUpload(UploadUi(name = file.name, error = PrinterWords.forFailure(r))) }
            }
        }
    }

    /** Stops the upload in progress, or drops the chosen file; nothing is left on screen about it. (Jarvis throws away a half-received file.) */
    fun cancelUpload() {
        uploadJob?.cancel(); uploadJob = null
        chosen = null
        setUpload(UploadUi())
    }

    /** The owner read the result of the last upload. */
    fun clearUpload() { if (uploadJob?.isActive != true) { chosen = null; setUpload(UploadUi()) } }

    private fun setUpload(u: UploadUi) = _ui.update { it.copy(files = it.files.copy(upload = u)) }

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
        /**
         * The wait between camera pictures (the next one is asked for after the last arrived, so the real rate is a little lower). 700 ms is about
         * 1.4 pictures a second, up from 1 a second. Two limits sit above this: Jarvis keeps a picture for 500 ms (asking more often only gets the
         * same one), and it allows 120 requests a minute per phone for everything except the long polls (spec part 5), so this leaves room for
         * commands and the other cards. Real live video is the camera stream of stage 4 (E14), which this does not replace.
         */
        const val FRAME_MS = 700L
    }
}

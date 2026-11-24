package com.edith.app.printer.feed

import com.edith.app.jarvis.FeedResult
import kotlinx.io.Source

/**
 * The routes of the printer feed as the rest of the app uses them. [PrinterFeedApi] is the real one; tests use fakes, so the
 * session and the page can be checked without a network. See `PrinterFeedApi` for what each function means.
 */
interface PrinterRoutes {
    suspend fun state(after: Long?, epoch: Long?, waitS: Int, quiet: Boolean = false): FeedResult<StateAnswer>
    suspend fun temperatures(): FeedResult<List<TempSeries>>
    suspend fun macros(): FeedResult<MacrosAnswer>
    suspend fun console(after: Long? = null, count: Int = 200): FeedResult<ConsoleAnswer>
    suspend fun files(fresh: Boolean): FeedResult<FilesAnswer>
    suspend fun fileDetail(path: String): FeedResult<PrinterFileDetail>
    suspend fun history(start: Int): FeedResult<HistoryAnswer>
    suspend fun snapshot(cameraId: String): FeedResult<ByteArray>
    suspend fun controls(on: Boolean): FeedResult<ArmState>
    suspend fun command(command: PrinterCommand, via: String, confirmed: Boolean, dryRun: Boolean, requestId: String?): CommandAnswer
    suspend fun events(after: Long?, waitS: Int): FeedResult<EventsAnswer>
    suspend fun settings(presets: List<Preset>?, hiddenMacros: List<String>?): FeedResult<SettingsAnswer>
    /** Sends a file (stage 3); [onProgress] gets the bytes sent so far. Cancelling the coroutine aborts the request. */
    suspend fun upload(name: String, source: Source, size: Long, onProgress: (Long) -> Unit = {}): FeedResult<String>
}

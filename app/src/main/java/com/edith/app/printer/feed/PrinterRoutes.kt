package com.edith.app.printer.feed

import com.edith.app.jarvis.FeedResult
import java.io.InputStream

/**
 * The routes of the printer feed as the rest of the app uses them. [PrinterFeedApi] is the real one; tests use fakes, so the
 * session and the page can be checked without a network. See `PrinterFeedApi` for what each function means.
 */
internal interface PrinterRoutes {
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
    suspend fun upload(name: String, source: InputStream, size: Long): FeedResult<String>
}

package com.edith.app.printer.feed

import com.edith.app.jarvis.FeedResult
import kotlinx.coroutines.channels.Channel
import java.io.InputStream

/** Fakes for the printer tests: routes that answer `state` from a queue (an empty queue is a held poll), and a feed with scripted access. */
internal class Asked(val after: Long?, val epoch: Long?, val waitS: Int, val quiet: Boolean)

/** Answers `state` from a queue, so a test decides when Jarvis "answers" (a held poll is a queue with nothing in it). */
internal class FakePrinterRoutes : PrinterRoutes {
    val answers = Channel<FeedResult<StateAnswer>>(Channel.UNLIMITED)
    val asked = mutableListOf<Asked>()
    val controlsCalls = mutableListOf<Boolean>()
    override suspend fun state(after: Long?, epoch: Long?, waitS: Int, quiet: Boolean): FeedResult<StateAnswer> {
        asked += Asked(after, epoch, waitS, quiet)
        return answers.receive()
    }
    override suspend fun controls(on: Boolean): FeedResult<ArmState> { controlsCalls += on; return FeedResult.Ok(ArmState(on)) }
    // What the other routes answer, and what they were asked: tests set these.
    var temperaturesAnswer: FeedResult<List<TempSeries>> = FeedResult.Ok(emptyList())
    var historyAnswer: (Int) -> FeedResult<HistoryAnswer> = { FeedResult.Ok(HistoryAnswer()) }
    var filesAnswer: FeedResult<FilesAnswer> = FeedResult.Ok(FilesAnswer())
    var detailAnswer: FeedResult<PrinterFileDetail> = FeedResult.Ok(PrinterFileDetail())
    var consoleAnswer: (Long?) -> FeedResult<ConsoleAnswer> = { FeedResult.Ok(ConsoleAnswer()) }
    var snapshotAnswer: () -> FeedResult<ByteArray> = { FeedResult.Ok(byteArrayOf(1, 2, 3)) }
    var temperaturesCalls = 0
    val historyStarts = mutableListOf<Int>()
    val filesFresh = mutableListOf<Boolean>()
    val detailPaths = mutableListOf<String>()
    val consoleAfters = mutableListOf<Long?>()
    val snapshotIds = mutableListOf<String>()
    override suspend fun temperatures(): FeedResult<List<TempSeries>> { temperaturesCalls++; return temperaturesAnswer }
    override suspend fun macros() = error("not used")
    override suspend fun console(after: Long?, count: Int): FeedResult<ConsoleAnswer> { consoleAfters += after; return consoleAnswer(after) }
    override suspend fun files(fresh: Boolean): FeedResult<FilesAnswer> { filesFresh += fresh; return filesAnswer }
    override suspend fun fileDetail(path: String): FeedResult<PrinterFileDetail> { detailPaths += path; return detailAnswer }
    override suspend fun history(start: Int): FeedResult<HistoryAnswer> { historyStarts += start; return historyAnswer(start) }
    override suspend fun snapshot(cameraId: String): FeedResult<ByteArray> { snapshotIds += cameraId; return snapshotAnswer() }
    override suspend fun command(command: PrinterCommand, via: String, confirmed: Boolean, dryRun: Boolean, requestId: String?) = error("not used")
    override suspend fun events(after: Long?, waitS: Int) = error("not used")
    override suspend fun settings(presets: List<Preset>?, hiddenMacros: List<String>?) = error("not used")
    override suspend fun upload(name: String, source: InputStream, size: Long) = error("not used")
    fun say(answer: StateAnswer) { answers.trySend(FeedResult.Ok(answer)) }
    fun say(rev: Long, epoch: Long = 1, serverTime: Long = 0, armed: Boolean = false) {
        answers.trySend(FeedResult.Ok(StateAnswer(rev = rev, epoch = epoch, serverTime = serverTime, arm = ArmState(armed))))
    }
    fun fail(r: FeedResult<StateAnswer>) { answers.trySend(r) }
}

/** Hands out the scripted accesses in order, then repeats the last. */
internal class FakePrinterFeed(vararg script: PrinterAccess) : PrinterFeedSource {
    private val list = script.toList()
    var calls = 0
    override suspend fun access(): PrinterAccess = list[minOf(calls++, list.lastIndex)]
}


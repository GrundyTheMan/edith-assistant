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
    override suspend fun temperatures() = error("not used")
    override suspend fun macros() = error("not used")
    override suspend fun console(after: Long?, count: Int) = error("not used")
    override suspend fun files(fresh: Boolean) = error("not used")
    override suspend fun fileDetail(path: String) = error("not used")
    override suspend fun history(start: Int) = error("not used")
    override suspend fun snapshot(cameraId: String) = error("not used")
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


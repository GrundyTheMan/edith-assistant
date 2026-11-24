package com.edith.app.printer.feed

import com.edith.app.jarvis.FeedResult
import com.edith.app.jarvis.JarvisApi
import com.edith.app.jarvis.feedJson
import kotlinx.serialization.Serializable
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import kotlinx.io.Source

/** What sending a command came to. The mapping from the server's codes is part 7.5 of the chunk. */
sealed interface CommandAnswer {
    /** Jarvis sent it: [sent] is what it said it sent, [risk] how carefully it was asked. */
    data class Sent(val sent: String, val risk: String) : CommandAnswer
    /** A dry run: the check, nothing was sent. */
    data class Checked(val check: Check) : CommandAnswer
    /** The server wants the owner's yes first (`CONFIRM_REQUIRED`): show [Check.question], send again with `confirmed`. */
    data class NeedsConfirm(val check: Check) : CommandAnswer
    /** Jarvis's own refusal, in its words. */
    data class Refused(val reason: String) : CommandAnswer
    /** Controls are off (any more): the page switches its Controls switch off. */
    data object ControlsOff : CommandAnswer
    /** Anything else; [words] are for the owner, [code] is Jarvis's code (or UNREACHABLE / UNAUTHORIZED) for the page's logic. */
    data class Failed(val words: String, val code: String?) : CommandAnswer
}

@Serializable
private data class CommandRequest(
    val command: PrinterCommand,
    val via: String,
    val confirmed: Boolean = false,
    val dryRun: Boolean = false,
    val requestId: String? = null,
)

@Serializable private data class ControlsRequest(val on: Boolean)

@Serializable private data class SettingsRequest(val presets: List<Preset>? = null, val hiddenMacros: List<String>? = null)

/**
 * One function per route of the printer feed (C39 part 5.2), over the paired Jarvis's pinned client. Every function returns a
 * [FeedResult]; none throws for a network or server problem, and a cancelled coroutine cancels the HTTP call.
 */
class PrinterFeedApi(private val api: JarvisApi) : PrinterRoutes {
    private fun printer(vararg more: String) = listOf("printer") + more

    /**
     * The long poll (part 6.1 of the Jarvis spec 14.2). Without [after] it answers at once; with the current `rev` it is held up to [waitS]
     * (at most 25) and the answer then has the same `rev` when nothing changed. Send the [epoch] of the last answer. [quiet] when the
     * screen is off or the page isn't in front: Jarvis then sends live readings every 10 s instead of 2.
     */
    override suspend fun state(after: Long?, epoch: Long?, waitS: Int, quiet: Boolean): FeedResult<StateAnswer> {
        val query = buildMap {
            if (after != null) put("after", after.toString())
            if (epoch != null) put("epoch", epoch.toString())
            if (waitS > 0) put("wait", waitS.coerceAtMost(MAX_WAIT_S).toString())
            if (quiet) put("quiet", "1")
        }
        return api.getJson(printer("state"), query, StateAnswer.serializer(), readTimeoutS = (waitS.coerceAtMost(MAX_WAIT_S) + 15).toLong())
    }

    override suspend fun temperatures(): FeedResult<List<TempSeries>> = map(api.getJson(printer("temperatures"), serializer = TempAnswer.serializer())) { it.series }

    override suspend fun macros(): FeedResult<MacrosAnswer> = api.getJson(printer("macros"), serializer = MacrosAnswer.serializer())

    /** The newest [count] console lines, or only those after the line numbered [after]. */
    override suspend fun console(after: Long?, count: Int): FeedResult<ConsoleAnswer> {
        val query = buildMap {
            if (after != null) put("after", after.toString())
            put("count", count.toString())
        }
        return api.getJson(printer("console"), query, ConsoleAnswer.serializer())
    }

    /** The cached list, or a fresh one (which freezes the printer for several seconds: never while printing). */
    override suspend fun files(fresh: Boolean): FeedResult<FilesAnswer> =
        api.getJson(printer("files"), mapOf("fresh" to if (fresh) "1" else "0"), FilesAnswer.serializer(), readTimeoutS = 40)

    override suspend fun fileDetail(path: String): FeedResult<PrinterFileDetail> =
        api.getJson(printer("files", "detail"), mapOf("path" to path), PrinterFileDetail.serializer())

    override suspend fun history(start: Int): FeedResult<HistoryAnswer> =
        api.getJson(printer("history"), mapOf("start" to start.toString()), HistoryAnswer.serializer(), readTimeoutS = 40)

    /** A camera picture (JPEG or PNG bytes), about one a second. */
    override suspend fun snapshot(cameraId: String): FeedResult<ByteArray> =
        map(api.getBytes(printer("camera", cameraId, "snapshot"))) { it.body }

    override suspend fun controls(on: Boolean): FeedResult<ArmState> =
        map(api.postJson(printer("controls"), feedJson.encodeToString(ControlsRequest.serializer(), ControlsRequest(on)), ControlsAnswer.serializer())) { it.arm }

    /**
     * Sends a typed command. [via] is "phone" (the page) or "chat" (Edith's chat tools: only pause, resume, cancel and light). A dry run
     * asks the server what it would say, sending nothing. A real send needs a [requestId]: a new UUID for each command and **the same one
     * when the same send is retried**, so a retry after a timeout is not run twice.
     */
    override suspend fun command(command: PrinterCommand, via: String, confirmed: Boolean, dryRun: Boolean, requestId: String?): CommandAnswer {
        val body = commandJson.encodeToString(CommandRequest.serializer(), CommandRequest(command, via, confirmed, dryRun, if (dryRun) null else requestId))
        return when (val r = api.exchange(printer("command"), body = jsonBody(body)) { it.text }) {
            is FeedResult.Ok -> answerOf(r.value, dryRun)
            is FeedResult.Err -> when (r.code) {
                "CONFIRM_REQUIRED" -> runCatching { feedJson.decodeFromString(CheckErrorBody.serializer(), r.body).check }.getOrNull()
                    ?.let { CommandAnswer.NeedsConfirm(it) } ?: CommandAnswer.Failed(PrinterWords.UNEXPECTED, r.code)
                "CONTROLS_OFF" -> CommandAnswer.ControlsOff
                "REFUSED" -> CommandAnswer.Refused(PrinterWords.forError(r))
                else -> CommandAnswer.Failed(PrinterWords.forError(r), r.code)
            }
            FeedResult.Unreachable -> CommandAnswer.Failed(PrinterWords.UNREACHABLE, "UNREACHABLE")
            FeedResult.Unauthorized -> CommandAnswer.Failed(PrinterWords.UNAUTHORIZED, "UNAUTHORIZED")
        }
    }

    private fun answerOf(text: String, dryRun: Boolean): CommandAnswer = try {
        if (dryRun) CommandAnswer.Checked(feedJson.decodeFromString(Check.serializer(), text))
        else feedJson.decodeFromString(CommandSent.serializer(), text).let { CommandAnswer.Sent(it.sent, it.risk) }
    } catch (e: kotlinx.serialization.SerializationException) {
        CommandAnswer.Failed(PrinterWords.UNEXPECTED, "UNREADABLE")
    }

    /** The print alerts (stage 3): held up to [waitS]; take `seq` from the answer, always (it restarts when Jarvis does). */
    override suspend fun events(after: Long?, waitS: Int): FeedResult<EventsAnswer> {
        val query = buildMap {
            if (after != null) put("after", after.toString())
            if (waitS > 0) put("wait", waitS.coerceAtMost(MAX_WAIT_S).toString())
        }
        return api.getJson(printer("events"), query, EventsAnswer.serializer(), readTimeoutS = (waitS.coerceAtMost(MAX_WAIT_S) + 15).toLong())
    }

    /** Temperature presets and hidden macros, the two settings a phone may change (needs Controls). */
    override suspend fun settings(presets: List<Preset>?, hiddenMacros: List<String>?): FeedResult<SettingsAnswer> =
        api.postJson(printer("settings"), feedJson.encodeToString(SettingsRequest.serializer(), SettingsRequest(presets, hiddenMacros)), SettingsAnswer.serializer())

    /** Sends a file to the printer (stage 3): at most 64 MB, up to five minutes. Streams [source]; never reads it all into memory. */
    override suspend fun upload(name: String, source: Source, size: Long, onProgress: (Long) -> Unit): FeedResult<String> {
        val body = PrinterUpload.body(source, size, OCTET, onProgress)
        return map(api.postBytes(printer("files", "upload"), mapOf("name" to name), body, UPLOAD_SECONDS)) {
            runCatching { feedJson.decodeFromString(UploadAnswer.serializer(), it).path }.getOrDefault(name)
        }
    }

    private fun jsonBody(text: String): OutgoingContent = TextContent(text, JSON)

    private inline fun <A, B> map(r: FeedResult<A>, f: (A) -> B): FeedResult<B> = when (r) {
        is FeedResult.Ok -> FeedResult.Ok(f(r.value))
        is FeedResult.Err -> FeedResult.Err(r.status, r.code, r.message, r.body)
        FeedResult.Unreachable -> FeedResult.Unreachable
        FeedResult.Unauthorized -> FeedResult.Unauthorized
    }

    companion object {
        const val MAX_WAIT_S = 25
        const val UPLOAD_SECONDS = 300L
        private val JSON = ContentType.Application.Json.withCharset(Charsets.UTF_8)
        private val OCTET = ContentType.Application.OctetStream
    }
}

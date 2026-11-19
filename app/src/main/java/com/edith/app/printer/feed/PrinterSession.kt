package com.edith.app.printer.feed

import com.edith.app.core.time.Clock
import com.edith.app.jarvis.FeedResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/** What the Printer page shows (C39 part 4.3). */
internal sealed interface PrinterScreenState {
    data object NoJarvis : PrinterScreenState
    data object NotShared : PrinterScreenState
    data object Connecting : PrinterScreenState
    /**
     * The printer as Jarvis last said it. [offsetMs] is Jarvis's clock minus the phone's at that answer, so the Controls countdown is right
     * on a phone whose clock is off. [stale] when the page has left and come back and nothing newer has arrived yet.
     */
    data class Live(val data: StateAnswer, val receivedAt: Long, val offsetMs: Long, val stale: Boolean = false) : PrinterScreenState
    /** Edith cannot reach Jarvis: the last answer (if any) is shown greyed with [why]. */
    data class JarvisAway(val last: StateAnswer?, val why: String) : PrinterScreenState
}

/**
 * Holds the printer's state while the Printer page is on screen (C39 part 6). [attach] starts a loop that long-polls Jarvis's `state`
 * route and publishes each change; [detach] stops it. Nothing runs while the page is away. The loop never spins: an answer with an
 * unchanged revision arrives only after Jarvis's wait, and a failure waits 2, 4, 8 ... 30 seconds before asking again.
 */
internal class PrinterSession(
    private val feed: PrinterFeedSource,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<PrinterScreenState>(PrinterScreenState.Connecting)
    val state: StateFlow<PrinterScreenState> = _state.asStateFlow()

    /** The last good answer, kept while Jarvis is away and between visits to the page. */
    @Volatile var lastLive: StateAnswer? = null
        private set

    /** True when the screen is off or the page is not in front: Jarvis then sends live readings less often. */
    @Volatile var quiet: Boolean = false

    @Volatile private var routes: PrinterRoutes? = null
    private var job: Job? = null

    /** The page came on screen. */
    @Synchronized fun attach() {
        job?.cancel()
        val last = lastLive
        _state.value = if (last != null) live(last, stale = true) else PrinterScreenState.Connecting
        job = scope.launch { run() }
    }

    /** The page left: stop asking, and turn Controls off if they are on (best effort; Jarvis also ends them on its own timer). */
    @Synchronized fun detach() {
        job?.cancel()
        job = null
        val last = lastLive
        val r = routes
        if (last != null && _state.value is PrinterScreenState.Live) _state.value = live(last, stale = true)
        if (last?.arm?.armed == true && r != null) scope.launch { withContext(NonCancellable) { r.controls(false) } }
    }

    /** Asks again at once, without the wait (after a command, so the card updates without waiting for the next change). */
    @Synchronized fun refreshNow() {
        if (job == null) return
        job?.cancel()
        job = scope.launch { run() }
    }

    private fun live(a: StateAnswer, stale: Boolean = false): PrinterScreenState.Live {
        val now = clock.now().toEpochMilli()
        return PrinterScreenState.Live(a, now, a.serverTime - now, stale)
    }

    private fun away(why: String) {
        _state.value = PrinterScreenState.JarvisAway(lastLive, why)
    }

    private suspend fun run() {
        var failures = 0
        var access: PrinterAccess? = null
        var rev: Long? = null
        var epoch: Long? = null
        while (scope.isActive) {
            if (access == null) access = feed.access()
            when (val a = access) {
                PrinterAccess.NoJarvis -> { _state.value = PrinterScreenState.NoJarvis; return }
                PrinterAccess.NotShared -> { _state.value = PrinterScreenState.NotShared; return }
                is PrinterAccess.Away -> {
                    away(a.why)
                    if (a.removed) return
                    failures++
                    delay(backoff(failures))
                    access = null
                }
                is PrinterAccess.Ready -> {
                    routes = a.api
                    when (val r = a.api.state(after = rev, epoch = epoch, waitS = if (rev == null) 0 else WAIT_S, quiet = quiet)) {
                        is FeedResult.Ok -> {
                            failures = 0
                            val answer = r.value
                            val current = _state.value
                            // An unchanged revision is Jarvis's "nothing new": nothing to publish, unless the page is showing something older.
                            val changed = answer.rev != rev || answer.epoch != epoch || current !is PrinterScreenState.Live || current.stale
                            rev = answer.rev
                            epoch = answer.epoch
                            if (changed) {
                                lastLive = answer
                                _state.value = live(answer)
                            }
                        }
                        is FeedResult.Err -> when {
                            r.code == "PRINTER_NOT_SHARED" -> { _state.value = PrinterScreenState.NotShared; return }
                            r.status == 429 -> delay(TOO_MANY_WAIT_MS)
                            else -> { failures++; away(PrinterWords.forError(r)); rev = null; delay(backoff(failures)) }
                        }
                        FeedResult.Unauthorized -> { away(PrinterWords.UNAUTHORIZED); return }
                        FeedResult.Unreachable -> { failures++; away(PrinterWords.UNREACHABLE); rev = null; access = null; delay(backoff(failures)) }
                    }
                }
                null -> return
            }
        }
    }

    companion object {
        /** Jarvis holds a poll up to 25 s; 20 leaves room for the network. */
        const val WAIT_S = 20
        const val TOO_MANY_WAIT_MS = 3_000L

        /** After the n-th failure in a row: 2 s, 4 s, 8 s ... at most 30 s. */
        fun backoff(failures: Int): Long = min(2_000L shl (failures - 1).coerceIn(0, 10), 30_000L)
    }
}

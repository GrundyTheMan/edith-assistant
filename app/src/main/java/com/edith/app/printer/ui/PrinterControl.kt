package com.edith.app.printer.ui

import com.edith.app.core.time.Clock
import com.edith.app.printer.feed.ArmState
import com.edith.app.printer.feed.Check
import com.edith.app.printer.feed.CommandAnswer
import com.edith.app.printer.feed.Connections
import com.edith.app.printer.feed.PrinterCommand
import com.edith.app.printer.feed.PrinterScreenState
import com.edith.app.printer.feed.PrinterSession
import com.edith.app.printer.feed.PrinterSettle
import com.edith.app.printer.feed.PrinterState
import com.edith.app.printer.feed.PrinterWords
import com.edith.app.printer.feed.Risks
import com.edith.app.printer.feed.SettleInfo
import com.edith.app.printer.feed.StateAnswer
import com.edith.app.printer.feed.kind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** The owner's yes is being asked for: [question] and [confirm] are Jarvis's own words (C39 part 7.2). */
internal data class ConfirmUi(
    val question: String,
    val confirm: String,
    val danger: Boolean,
    internal val command: PrinterCommand,
    internal val requestId: String,
) {
    /** The other button: never the same word as the confirm button, never "OK" or "Yes". */
    val keepLabel: String get() = if (confirm.contains("Cancel", ignoreCase = true)) "Keep printing" else "Keep going"
}

/** A command that was sent and whose change the page is waiting to see (its button shows a spinner). */
internal data class PendingCommand(val command: PrinterCommand, val before: PrinterState, val sentAtMs: Long, val sawMotion: Boolean = false)

internal data class ControlUi(
    /** The owner allows this phone to control the printer (Jarvis's switch and this phone's tick). When false nothing that sends is drawn. */
    val mayControl: Boolean = false,
    /** Controls are on (the server's answer, or what we were just told while waiting for the next state). */
    val armed: Boolean = false,
    val armUntilMs: Long? = null,
    /** Jarvis's clock minus the phone's, for the countdown. */
    val offsetMs: Long = 0,
    val sheet: ConfirmUi? = null,
    /** The control keys (see [controlKey]) whose command was sent and has not shown yet. */
    val pending: Set<String> = emptySet(),
    /** A line for the owner: the server's refusal, or why Controls went off. Shown once. */
    val message: String? = null,
    /** The server's refusal under the control that asked (a jog before homing: "Home the printer first."). */
    val refused: Map<String, String> = emptyMap(),
    /** Whether a send is in flight. */
    val busy: Boolean = false,
)

/** Which control a command belongs to: its pending spinner and its inline refusal are kept under this key. */
internal fun controlKey(c: PrinterCommand): String = when (c) {
    is PrinterCommand.Home -> "home:${c.axes}"
    is PrinterCommand.Jog -> "jog"
    is PrinterCommand.SetTemp -> "setTemp:${c.heater}"
    is PrinterCommand.Preset -> "preset:${c.name}"
    is PrinterCommand.Fan -> "fan:${c.fan}"
    is PrinterCommand.Macro -> "macro:${c.name}"
    else -> c.kind
}

/**
 * Controls, the confirm flow and the pending buttons of the Printer page (C39 stage 2, parts 7.1 to 7.3). The server decides everything:
 * what is allowed, what needs the owner's yes. This only asks, shows the server's words, and never sends `confirmed = true` unless the owner
 * pressed the confirm button of a sheet that showed the server's question.
 */
internal class PrinterControl(
    private val session: PrinterSession,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val _ui = MutableStateFlow(ControlUi())
    val ui: StateFlow<ControlUi> = _ui.asStateFlow()

    private val pending = LinkedHashMap<String, PendingCommand>()
    private val timeouts = HashMap<String, Job>()
    private var sheetJob: Job? = null
    private var armLocal: ArmState? = null
    private var lastRev = -1L

    init {
        scope.launch { session.state.collect { s -> onState(s) } }
        scope.launch { session.shared.collect { shared -> _ui.update { it.copy(mayControl = shared?.control == true) } } }
    }

    // --- Controls ---------------------------------------------------------------------------------------------------

    /** The Controls switch. On: Jarvis starts a timer for this phone. Off: Jarvis ends it. Either way the page follows Jarvis's answer. */
    fun setControls(on: Boolean) {
        val routes = session.routes() ?: return
        scope.launch {
            when (val r = routes.controls(on)) {
                is com.edith.app.jarvis.FeedResult.Ok -> setArm(r.value)
                else -> _ui.update { it.copy(message = PrinterWords.forFailure(r)) }
            }
            session.refreshNow()
        }
    }

    private fun setArm(arm: ArmState) {
        armLocal = arm
        _ui.update { it.copy(armed = arm.armed, armUntilMs = arm.until) }
    }

    /** Controls end with the page, a printer that is not ready, or Jarvis being away; Jarvis's timer is the guarantee, this is the courtesy. */
    private fun disarmQuietly() {
        val routes = session.routes() ?: return
        armLocal = ArmState(false)
        _ui.update { it.copy(armed = false, armUntilMs = null) }
        scope.launch { runCatching { routes.controls(false) } }
    }

    // --- Sending ---------------------------------------------------------------------------------------------------

    /** Asks Jarvis to send [command]. A command the owner must confirm opens the sheet; nothing is confirmed here. */
    fun send(command: PrinterCommand, requestId: String = UUID.randomUUID().toString()) {
        val routes = session.routes() ?: return
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, message = null, refused = it.refused - controlKey(command)) }
        scope.launch {
            try {
                handle(command, requestId, routes.command(command, VIA_PHONE, confirmed = false, dryRun = false, requestId = requestId), confirmedAlready = false)
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    /** The owner pressed the confirm button of the open sheet. */
    fun confirm() {
        val sheet = _ui.value.sheet ?: return
        val routes = session.routes() ?: return
        closeSheet()
        _ui.update { it.copy(busy = true) }
        scope.launch {
            try {
                handle(sheet.command, sheet.requestId, routes.command(sheet.command, VIA_PHONE, confirmed = true, dryRun = false, requestId = sheet.requestId), confirmedAlready = true)
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    /** Keep going: the sheet closes and nothing is sent. */
    fun dismissSheet() = closeSheet()

    private fun closeSheet() {
        sheetJob?.cancel(); sheetJob = null
        _ui.update { it.copy(sheet = null) }
    }

    private fun handle(command: PrinterCommand, requestId: String, answer: CommandAnswer, confirmedAlready: Boolean) {
        val key = controlKey(command)
        when (answer) {
            is CommandAnswer.Sent -> sent(command)
            is CommandAnswer.NeedsConfirm ->
                if (confirmedAlready) _ui.update { it.copy(message = "Jarvis asked again. Try again.") }
                else openSheet(command, requestId, answer.check)
            CommandAnswer.ControlsOff -> {
                armLocal = ArmState(false)
                _ui.update { it.copy(armed = false, armUntilMs = null, message = PrinterWords.CONTROLS_OFF) }
            }
            is CommandAnswer.Refused -> _ui.update { it.copy(message = answer.reason, refused = it.refused + (key to answer.reason)) }
            is CommandAnswer.Failed -> _ui.update { it.copy(message = answer.words) }
            is CommandAnswer.Checked -> Unit
        }
    }

    private fun openSheet(command: PrinterCommand, requestId: String, check: Check) {
        val confirm = if (command is PrinterCommand.EStop) "Stop now" else check.confirm?.takeIf { it.isNotBlank() } ?: "Confirm"
        val question = check.question?.takeIf { it.isNotBlank() } ?: "Send ${command.kind}?"
        _ui.update { it.copy(sheet = ConfirmUi(question, confirm, danger = check.risk == Risks.DANGER, command, requestId)) }
        sheetJob?.cancel()
        sheetJob = scope.launch { delay(SHEET_MS); _ui.update { it.copy(sheet = null) } }   // a sheet nobody answers closes itself (10 s)
    }

    // --- Pending buttons ---------------------------------------------------------------------------------------------

    /** Told of every command Jarvis accepted (the Files sheet closes on Print, Add to queue and Delete). Set by the ViewModel. */
    @Volatile var onSent: (PrinterCommand) -> Unit = {}

    private fun sent(command: PrinterCommand) {
        onSent(command)
        val key = controlKey(command)
        val timeout = PrinterSettle.timeoutMs(command)
        val before = (session.state.value as? PrinterScreenState.Live)?.data?.state
        if (timeout > 0 && before != null) {
            pending[key] = PendingCommand(command, before, clock.now().toEpochMilliseconds())
            timeouts.remove(key)?.cancel()
            timeouts[key] = scope.launch { delay(timeout); finish(key) }
            publishPending()
        }
        session.refreshNow()
    }

    private fun finish(key: String) {
        pending.remove(key); timeouts.remove(key)?.cancel()
        publishPending()
    }

    private fun publishPending() = _ui.update { it.copy(pending = pending.keys.toSet()) }

    // --- The live state --------------------------------------------------------------------------------------------------

    private fun onState(s: PrinterScreenState) {
        when (s) {
            is PrinterScreenState.Live -> if (!s.stale) onAnswer(s.data, s.offsetMs)
            is PrinterScreenState.JarvisAway -> { if (_ui.value.armed) { armLocal = ArmState(false); _ui.update { it.copy(armed = false, armUntilMs = null) } }; clearPending() }
            else -> Unit
        }
    }

    private fun onAnswer(a: StateAnswer, offsetMs: Long) {
        if (a.rev == lastRev && armLocal == null) return
        lastRev = a.rev
        // Jarvis's word beats what we guessed from our own answer.
        armLocal = null
        _ui.update { it.copy(armed = a.arm.armed, armUntilMs = a.arm.until, offsetMs = offsetMs) }
        if (a.arm.armed && a.state.connection != Connections.READY) disarmQuietly()
        val now = clock.now().toEpochMilliseconds()
        for ((key, p) in pending.toMap()) {
            val saw = p.sawMotion || a.state.toolhead.liveVelocity >= 1.0
            val updated = p.copy(sawMotion = saw)
            pending[key] = updated
            val presets = session.shared.value?.presets.orEmpty()
            if (PrinterSettle.settled(p.command, p.before, a.state, SettleInfo(now - p.sentAtMs, saw, presets))) finish(key)
        }
    }

    private fun clearPending() { for (j in timeouts.values) j.cancel(); timeouts.clear(); pending.clear(); publishPending() }

    /** A line was shown: it is not shown again. */
    fun messageShown() = _ui.update { it.copy(message = null) }

    /** The page left the screen: nothing pending carries over, and the sheet closes. */
    fun detached() {
        closeSheet(); clearPending()
        armLocal = null
        _ui.update { it.copy(armed = false, armUntilMs = null, message = null, refused = emptyMap(), busy = false) }
    }

    companion object {
        const val VIA_PHONE = "phone"
        const val SHEET_MS = 10_000L
    }
}

package com.edith.app.home

import com.edith.app.chat.engine.ConfirmationRequest
import com.edith.app.printer.feed.CommandAnswer
import com.edith.app.printer.feed.PrinterAccess
import com.edith.app.printer.feed.PrinterCommand
import com.edith.app.printer.feed.PrinterFeedSource
import com.edith.app.printer.feed.PrinterWords
import com.edith.app.tools.framework.ParamType
import com.edith.app.tools.framework.Tool
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolParam
import com.edith.app.tools.framework.ToolResult
import com.edith.app.tools.framework.ToolSpec
import kotlin.math.roundToLong
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * `printer_control(action, level?)` (C39 part 10, D26): pause, resume or cancel the owner's print, or set the printer's light, through
 * Jarvis, so it works away from home. It is Jarvis's chat path: Jarvis accepts only these four, needs no Controls switch on the phone, and
 * still refuses to send without the owner's yes. So **every call shows a card first**, and the card's words are Jarvis's own (a dry run
 * asks what it would say). Cancelling needs a tap on the screen, never a voice. Home's `home_control` on the printer is unchanged: it is
 * for the printer on the home Wi-Fi; this one is for anywhere.
 */
@OptIn(ExperimentalUuidApi::class)
class PrinterControlTool(
    private val feed: PrinterFeedSource,
    /** Whether the paired Jarvis shares the printer with control, as last read: when it does not, the tool is not offered at all. */
    private val mayControl: () -> Boolean,
) : Tool {
    override val spec = ToolSpec(
        "printer_control",
        "Pause, resume or cancel the owner's 3D print, or set the printer's light, through Jarvis (works away from home). Every call shows the owner a card first.",
        listOf(
            ToolParam("action", ParamType.String, "What to do", required = true, enumValues = ACTIONS, aliases = listOf("command")),
            ToolParam("level", ParamType.String, "For light: 0 (off) to 100 (full); without it the light goes to 100", aliases = listOf("brightness", "value", "percent")),
        ),
    )
    override val statusLabel = "Working on the printer…"
    override val displayName = "printer"
    override val guidance = "For a printer action call printer_control straight away; don't ask the owner to confirm in chat: the app shows a card."

    /** Not offered unless Jarvis lets this phone control the printer (so the model is never told it can do what it cannot). */
    override fun offered(): Boolean = mayControl()

    override fun statusLabelFor(args: ToolArgs): String = when (args.string("action")?.trim()?.lowercase()) {
        "pause" -> "Pausing the print…"
        "resume" -> "Resuming the print…"
        "cancel" -> "Cancelling the print…"
        "light" -> "Setting the printer's light…"
        else -> statusLabel
    }

    private sealed interface Parsed {
        data class Ok(val command: PrinterCommand, val action: String) : Parsed
        data class Bad(val why: String) : Parsed
    }

    private fun parse(args: ToolArgs): Parsed {
        val action = args.string("action")?.trim()?.lowercase().orEmpty()
        return when (action) {
            "pause" -> Parsed.Ok(PrinterCommand.Pause, action)
            "resume" -> Parsed.Ok(PrinterCommand.Resume, action)
            "cancel" -> Parsed.Ok(PrinterCommand.Cancel, action)
            "light" -> {
                val level = args.string("level")?.trim()?.removeSuffix("%")?.trim()?.takeIf { it.isNotEmpty() }
                val percent = if (level == null) 100.0 else level.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
                    ?: return Parsed.Bad("The light level must be a number from 0 to 100.")
                Parsed.Ok(PrinterCommand.Light(percent.coerceIn(0.0, 100.0) / 100.0), action)
            }
            else -> Parsed.Bad("The action must be pause, resume, cancel or light.")
        }
    }

    /** What Edith can do right now, or the words to say why not. */
    private suspend fun ready(): Pair<PrinterAccess.Ready?, String?> = when (val a = feed.access()) {
        PrinterAccess.NoJarvis -> null to "The owner hasn't paired Edith with Jarvis, so she can't reach the printer away from home."
        PrinterAccess.NotShared -> null to PrinterWords.NOT_SHARED
        is PrinterAccess.Away -> null to a.why
        is PrinterAccess.Ready -> if (a.shared.control) a to null else null to PrinterWords.CONTROL_OFF
    }

    override suspend fun confirmation(args: ToolArgs): ConfirmationRequest? {
        val p = parse(args) as? Parsed.Ok ?: return null   // execute explains
        val r = ready().first ?: return null
        // A dry run: Jarvis says what it would ask, and whether it would refuse (then there is no card, and execute says why).
        val check = (r.api.command(p.command, VIA_CHAT, confirmed = false, dryRun = true, requestId = null) as? CommandAnswer.Checked)?.check ?: return null
        if (!check.allowed) return null
        val cancel = p.command is PrinterCommand.Cancel
        return ConfirmationRequest(
            title = check.question?.takeIf { it.isNotBlank() } ?: fallbackTitle(p),
            details = listOf("Printer" to r.shared.name.ifBlank { "3D printer" }),
            confirmLabel = check.confirm?.takeIf { it.isNotBlank() } ?: fallbackConfirm(p),
            // "Cancel" on both buttons would mean "stop the print" on one and "keep printing" on the other.
            denyLabel = if (cancel) "Keep printing" else "Cancel",
            screenOnly = cancel,
        )
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val p = when (val parsed = parse(args)) {
            is Parsed.Bad -> return ToolResult.Error(parsed.why)
            is Parsed.Ok -> parsed
        }
        val (r, why) = ready()
        if (r == null) return ToolResult.Error(why.orEmpty())
        return when (val a = r.api.command(p.command, VIA_CHAT, confirmed = true, dryRun = false, requestId = Uuid.random().toString())) {
            is CommandAnswer.Sent -> ToolResult.Ok("Done: ${done(p)}. Jarvis sent ${a.sent}.")
            is CommandAnswer.Refused -> ToolResult.Error(a.reason)
            is CommandAnswer.Failed -> ToolResult.Error(a.words)
            CommandAnswer.ControlsOff -> ToolResult.Error(PrinterWords.CONTROLS_OFF)
            is CommandAnswer.NeedsConfirm -> ToolResult.Error("Jarvis asked again; nothing was done. Ask the owner to try again.")
            is CommandAnswer.Checked -> ToolResult.Error("Nothing was sent.")
        }
    }

    private fun done(p: Parsed.Ok): String = when (val c = p.command) {
        PrinterCommand.Pause -> "paused the print"
        PrinterCommand.Resume -> "resumed the print"
        PrinterCommand.Cancel -> "cancelled the print"
        is PrinterCommand.Light -> "set the printer's light to ${(c.level * 100).roundToLong()}%"
        else -> p.action
    }

    private fun fallbackTitle(p: Parsed.Ok): String = when (val c = p.command) {
        PrinterCommand.Pause -> "Pause the print?"
        PrinterCommand.Resume -> "Resume the print?"
        PrinterCommand.Cancel -> "Cancel the print? It can't be continued."
        is PrinterCommand.Light -> if (c.level <= 0.0) "Turn the printer's light off?" else "Set the printer's light to ${(c.level * 100).roundToLong()}%?"
        else -> "Send this to the printer?"
    }

    private fun fallbackConfirm(p: Parsed.Ok): String = when (p.command) {
        PrinterCommand.Pause -> "Pause"
        PrinterCommand.Resume -> "Resume"
        PrinterCommand.Cancel -> "Cancel print"
        else -> "Set light"
    }

    companion object {
        const val VIA_CHAT = "chat"
        val ACTIONS = listOf("pause", "resume", "cancel", "light")
    }
}

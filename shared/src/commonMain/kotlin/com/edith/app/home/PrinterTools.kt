package com.edith.app.home

import com.edith.app.tools.framework.Tool
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolResult
import com.edith.app.tools.framework.ToolSpec

/**
 * `printer_status()`: how the owner's 3D printer is doing (C34, Jarvis's `printer_status`): what it is printing, how far along,
 * the time left, temperatures and any error. Pause, resume and cancel are `home_control` on the printer (cancel shows a card).
 */
class PrinterStatusTool(private val home: Home, private val now: () -> Long = { com.edith.app.core.time.SystemClock.now().toEpochMilliseconds() }) : Tool {
    override val spec = ToolSpec(
        "printer_status",
        "How the owner's 3D printer is doing: what it is printing, how far along, time left, nozzle and bed temperatures, and any error. " +
            "Use it for \"how's the print going?\" or \"is the printer done?\". To pause, resume or cancel, use home_control on the printer.",
        emptyList(),
    )
    override val statusLabel = "Checking the printer…"
    override val displayName = "printer"
    override val integrationId = Home.ID

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val view = home.devicesForTools()
        val printers = view.devices.filter { it.type == DeviceType.Printer }
        if (printers.isEmpty()) return ToolResult.Ok("No 3D printer is connected. The owner can connect one in Settings → Home.")
        return ToolResult.Ok(printers.joinToString("\n") { describe(it, view) })
    }

    private fun describe(d: HomeDevice, view: HomeView): String {
        val state = d.state
        val last = state?.let { s -> HomeActions.stateText(d, s).takeIf { it.isNotEmpty() } }
        val at = d.stateAt
        return when {
            !d.online && last == null -> "${d.name}: not reachable. It works on the home Wi-Fi, or it is off."
            !d.online -> "${d.name}: not reachable now (it works on the home Wi-Fi). Last reported${at?.let { " ${HomeActions.age(it, now())}" }.orEmpty()}: $last."
            last == null -> "${d.name}: reachable, but it reported no status."
            d.backend in view.stale && at != null -> "${d.name}: $last (last reported ${HomeActions.age(at, now())}; no answer just now)."
            else -> "${d.name}: $last${extras(state)}."
        }
    }

    /** Through Jarvis it says so; and the printer's own latest error line is mentioned even while it is still printing (C39 9.3). */
    private fun extras(state: DeviceState?): String {
        val job = state?.print ?: return ""
        val alert = job.message?.takeIf { it.isNotBlank() && job.state != PrintState.Error && job.state != PrintState.Cancelled }
        return (if (job.viaJarvis) " (via Jarvis)" else "") + (alert?.let { ". The printer's latest error line: $it" } ?: "")
    }
}

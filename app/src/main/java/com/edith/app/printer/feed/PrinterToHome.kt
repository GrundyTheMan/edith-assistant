package com.edith.app.printer.feed

import com.edith.app.home.DeviceState
import com.edith.app.home.Heat
import com.edith.app.home.PrintJob
import com.edith.app.home.PrintState
import com.edith.app.jarvis.FeedResult
import com.edith.app.printer.JarvisPrinterRead

/** What Jarvis says about the printer, in the shape the Home tab and Edith's printer tool already read (C39 part 9.3). */
internal object PrinterToHome {
    /**
     * The printer's state as a Home device state, or null when Jarvis itself cannot see the printer (it is off, or none is set up):
     * then the tile stays "not reachable", as before. A Klipper that is starting, shut down or in error is an error job with
     * Jarvis's own words. The printer's own latest error line is the job's message when Klipper sent no other.
     */
    fun deviceState(s: PrinterState): DeviceState? {
        when (s.connection) {
            Connections.UNREACHABLE, Connections.NONE, Connections.CONNECTING -> return null
            Connections.READY -> Unit
            else -> return DeviceState(print = PrintJob(PrintState.Error, message = s.message?.takeIf { it.isNotBlank() } ?: "Klipper isn't ready", viaJarvis = true))
        }
        val state = when (s.print.state) {
            PrinterStates.PRINTING -> PrintState.Printing
            PrinterStates.PAUSED -> PrintState.Paused
            PrinterStates.COMPLETE -> PrintState.Complete
            PrinterStates.CANCELLED -> PrintState.Cancelled
            PrinterStates.ERROR -> PrintState.Error
            else -> PrintState.Idle
        }
        val active = state == PrintState.Printing || state == PrintState.Paused
        val job = PrintJob(
            state = state,
            title = s.print.title?.takeIf { it.isNotBlank() },
            progress = s.print.progress.coerceIn(0.0, 1.0),
            timeLeftSeconds = if (active) s.print.timeLeft?.toLong() else null,
            layer = s.print.layer?.current,
            layers = s.print.layer?.total,
            message = s.print.message?.takeIf { it.isNotBlank() } ?: s.alert?.text?.takeIf { it.isNotBlank() },
            viaJarvis = true,
        )
        return DeviceState(print = job, heaters = s.heaters.map { Heat(it.label, it.temperature, it.target) })
    }
}

/**
 * Asks Jarvis how the printer is, once, when the printer did not answer directly. Nothing here runs when the direct read works, or
 * when no Jarvis is paired, the printer is not shared, or Jarvis cannot be reached: all of those are "no answer" (null).
 */
internal class FeedPrinterRead(private val feed: PrinterFeedSource) : JarvisPrinterRead {
    override suspend fun read(): DeviceState? {
        val ready = feed.access() as? PrinterAccess.Ready ?: return null
        val answer = ready.api.state(after = null, epoch = null, waitS = 0, quiet = true) as? FeedResult.Ok ?: return null
        return PrinterToHome.deviceState(answer.value.state)
    }
}

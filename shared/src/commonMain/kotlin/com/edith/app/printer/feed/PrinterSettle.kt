package com.edith.app.printer.feed

import kotlin.math.abs
import kotlin.math.roundToLong

/** What the settle rules need besides the two states: time since the send, and whether the toolhead has moved since. */
data class SettleInfo(
    /** Milliseconds since the command was sent. */
    val elapsedMs: Long,
    /** True once a state has shown the toolhead moving since the send. */
    val sawMotion: Boolean,
    /** The phone's temperature presets (Jarvis's), so a preset knows the targets it should reach. */
    val presets: List<Preset> = emptyList(),
)

/**
 * "Has the printer shown the change this command asked for?" (C39 part 7.3): a port of Jarvis's `commandSettled` and `settleTimeout`
 * (`src/shared/printerCommands.ts`), with its constants. After a command is sent, its button shows a spinner until this says yes or
 * [timeoutMs] passes. It only reads the state: the server decides what is allowed.
 */
object PrinterSettle {
    /** mm/s: below this the toolhead counts as standing still. */
    private const val STILL_SPEED = 1.0
    /** ZMOD reports the axes homed at once, before the toolhead has moved: with no movement seen, homing counts as done only after this long. */
    private const val HOME_NO_MOTION_MS = 5_000L

    /** How long a button waits for its change to show before giving up; 0 means it does not wait at all. */
    fun timeoutMs(c: PrinterCommand): Long = when (c) {
        is PrinterCommand.Home, PrinterCommand.Pause, PrinterCommand.Resume, PrinterCommand.Cancel -> 120_000
        is PrinterCommand.Start -> 60_000
        is PrinterCommand.Jog -> 30_000
        is PrinterCommand.SetTemp, is PrinterCommand.Preset, PrinterCommand.HeatersOff -> 15_000
        is PrinterCommand.Light, is PrinterCommand.Fan, is PrinterCommand.SpeedFactor, is PrinterCommand.FlowFactor, is PrinterCommand.ZOffset,
        is PrinterCommand.QueueAdd, is PrinterCommand.QueueRemove, PrinterCommand.QueueStart, PrinterCommand.QueuePause -> 10_000
        else -> 0
    }

    /** Whether [now] shows what [c] asked for, compared with [before], the state when it was sent. */
    fun settled(c: PrinterCommand, before: PrinterState, now: PrinterState, info: SettleInfo): Boolean {
        fun near(a: Double, b: Double, by: Double) = abs(a - b) <= by
        val still = now.toolhead.liveVelocity < STILL_SPEED
        fun target(id: String): Double? = now.heaters.firstOrNull { it.id == id }?.target
        fun pos(s: PrinterState, i: Int) = s.toolhead.position.getOrElse(i) { 0.0 }
        return when (c) {
            is PrinterCommand.Home -> c.axes.all { now.toolhead.homed.contains(it) } && still && (info.sawMotion || info.elapsedMs >= HOME_NO_MOTION_MS)
            is PrinterCommand.Jog -> {
                val i = when (c.axis) { Axis.X -> 0; Axis.Y -> 1; else -> 2 }
                still && near(pos(now, i), pos(before, i) + c.distance, 0.05)
            }
            is PrinterCommand.SetTemp -> target(c.heater) == c.target.roundToLong().toDouble()
            is PrinterCommand.Preset -> {
                val p = info.presets.firstOrNull { it.name == c.name }
                p == null || (target("extruder") == p.extruder.toDouble() && target("heater_bed") == p.bed.toDouble())
            }
            PrinterCommand.HeatersOff -> now.heaters.all { it.target == 0.0 }
            PrinterCommand.Pause -> now.print.state == PrinterStates.PAUSED
            PrinterCommand.Resume -> now.print.state == PrinterStates.PRINTING && !now.print.paused
            PrinterCommand.Cancel -> now.print.state != PrinterStates.PRINTING && now.print.state != PrinterStates.PAUSED
            is PrinterCommand.Start -> now.print.state == PrinterStates.PRINTING || now.print.state == PrinterStates.PAUSED
            is PrinterCommand.Light -> now.light != null && near(now.light, c.level, 0.011)
            is PrinterCommand.Fan -> now.fans.firstOrNull { it.id == c.fan }.let { f -> f == null || near(f.speed, c.speed, 0.011) }
            is PrinterCommand.SpeedFactor -> near(now.toolhead.speedFactor * 100, c.percent, 0.5)
            is PrinterCommand.FlowFactor -> near(now.toolhead.extrudeFactor * 100, c.percent, 0.5)
            is PrinterCommand.ZOffset -> near(now.toolhead.zOffset, before.toolhead.zOffset + c.delta, 0.0005)
            is PrinterCommand.QueueAdd -> (now.queue?.jobs?.size ?: 0) > (before.queue?.jobs?.size ?: 0) || now.queue?.state != before.queue?.state
            is PrinterCommand.QueueRemove -> now.queue?.jobs?.none { it.id == c.jobId } != false
            PrinterCommand.QueueStart -> now.queue?.state != "paused"
            PrinterCommand.QueuePause -> now.queue?.state == "paused"
            else -> true
        }
    }
}

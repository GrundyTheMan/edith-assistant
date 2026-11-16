package com.edith.app.home

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** How a command needs the owner's OK (C20 §2, C25 §4). */
enum class Approval {
    /** Low risk, or making things safer (lock). */
    None,
    /** A card the owner taps; hands-free can't confirm it by voice either. */
    Card,
    /** A card that is screen-only by rule (D10): unlocking and opening the garage. */
    ScreenOnly,
}

/**
 * The tool's actions (Jarvis's `ALEXA_ACTIONS`, plus the garage's open/close) and the rules every command follows,
 * shared by the tools and the Home tab so both ask the same way.
 */
object HomeActions {
    val NAMES = listOf(
        "turn_on", "turn_off", "set_brightness", "set_percentage", "set_color", "set_white",
        "set_temperature", "set_thermostat_mode", "lock", "unlock", "open", "close", "pause", "resume", "cancel",
    )

    /** The command [action] with [value] means for [device], or why it can't be done, in words for the model. */
    fun parse(device: HomeDevice, action: String, value: String?): Result<DeviceCommand> = runCatching {
        val v = value?.trim()?.takeIf { it.isNotEmpty() }
        // What the device can't do at all is said first, before anything about the value.
        val (kind, words) = when (action.trim().lowercase()) {
            "set_color" -> DeviceCommand.SetColor("") to "set the colour"
            "set_white" -> DeviceCommand.SetWhite("") to "set a white tone"
            "set_temperature" -> DeviceCommand.SetTemperature(0.0) to "set a temperature"
            "set_thermostat_mode" -> DeviceCommand.SetMode("") to "change mode"
            else -> null to ""
        }
        if (kind != null && !can(device, kind)) fail("${device.name} can't $words. It can: ${actionsFor(device).joinToString()}")
        fun percent(): Int {
            val n = v?.removeSuffix("%")?.trim()?.toDoubleOrNull() ?: fail("Give value as a percentage from 0 to 100")
            if (n < 0 || n > 100) fail("Give value as a percentage from 0 to 100")
            return n.roundToInt()
        }
        val command = when (action.trim().lowercase()) {
            "turn_on" -> DeviceCommand.TurnOn
            "turn_off" -> DeviceCommand.TurnOff
            "set_brightness" -> DeviceCommand.SetBrightness(percent())
            "set_percentage" -> DeviceCommand.SetPercentage(percent())
            "set_color" -> {
                val names = device.capability<Capability.Color>()?.names.orEmpty()
                val name = v?.lowercase() ?: fail("Give value as a colour: ${names.joinToString()}")
                DeviceCommand.SetColor(names.firstOrNull { it == name } ?: fail("${device.name} has no colour \"$v\". It has: ${names.joinToString()}"))
            }
            "set_white" -> {
                val names = device.capability<Capability.WhiteTone>()?.names.orEmpty()
                val name = v?.lowercase() ?: fail("Give value as one of: ${names.joinToString()}")
                DeviceCommand.SetWhite(names.firstOrNull { it == name || it == "$name white" } ?: fail("${device.name} has no white \"$v\". It has: ${names.joinToString()}"))
            }
            "set_temperature" -> {
                val t = device.capability<Capability.Thermostat>()
                val n = v?.filter { it.isDigit() || it == '.' || it == '-' }?.toDoubleOrNull() ?: fail("Give value as a temperature")
                if (t != null && (n < t.min || n > t.max)) {
                    fail("${fmt(n)}${t.unit.symbol} is outside ${fmt(t.min)}–${fmt(t.max)}${t.unit.symbol}, the range Edith allows")
                }
                DeviceCommand.SetTemperature(n)
            }
            "set_thermostat_mode" -> {
                val modes = device.capability<Capability.Thermostat>()?.modes.orEmpty()
                val mode = v?.lowercase() ?: fail("Give value as a mode: ${modes.joinToString()}")
                DeviceCommand.SetMode(modes.firstOrNull { it == mode } ?: fail("${device.name} has no mode \"$v\". It has: ${modes.joinToString()}"))
            }
            "lock" -> DeviceCommand.Lock
            "unlock" -> DeviceCommand.Unlock
            "open" -> DeviceCommand.Open
            "close" -> DeviceCommand.Close
            "pause" -> DeviceCommand.Pause
            "resume" -> DeviceCommand.Resume
            "cancel" -> DeviceCommand.CancelPrint
            else -> fail("Unknown action \"$action\". Use one of: ${NAMES.joinToString()}")
        }
        if (!can(device, command)) fail("${device.name} can't ${label(command)}. It can: ${actionsFor(device).joinToString()}")
        command
    }

    fun can(device: HomeDevice, command: DeviceCommand): Boolean = when (command) {
        DeviceCommand.TurnOn, DeviceCommand.TurnOff -> device.has(Capability.OnOff)
        is DeviceCommand.SetBrightness -> device.has(Capability.Brightness)
        is DeviceCommand.SetPercentage -> device.has(Capability.Percentage)
        is DeviceCommand.SetColor -> device.capability<Capability.Color>() != null
        is DeviceCommand.SetWhite -> device.capability<Capability.WhiteTone>() != null
        is DeviceCommand.SetTemperature, is DeviceCommand.SetMode -> device.capability<Capability.Thermostat>() != null
        DeviceCommand.Lock, DeviceCommand.Unlock -> device.has(Capability.LockUnlock)
        DeviceCommand.Open, DeviceCommand.Close -> device.has(Capability.OpenClose)
        DeviceCommand.Pause, DeviceCommand.Resume, DeviceCommand.CancelPrint -> device.has(Capability.PrintControl)
    }

    /** The action names [device] supports, for "It can: …". */
    fun actionsFor(device: HomeDevice): List<String> = buildList {
        if (device.has(Capability.OnOff)) addAll(listOf("turn_on", "turn_off"))
        if (device.has(Capability.Brightness)) add("set_brightness")
        if (device.has(Capability.Percentage)) add("set_percentage")
        if (device.capability<Capability.Color>() != null) add("set_color")
        if (device.capability<Capability.WhiteTone>() != null) add("set_white")
        if (device.capability<Capability.Thermostat>() != null) addAll(listOf("set_temperature", "set_thermostat_mode"))
        if (device.has(Capability.LockUnlock)) addAll(listOf("lock", "unlock"))
        if (device.has(Capability.OpenClose)) addAll(listOf("open", "close"))
        if (device.has(Capability.PrintControl)) addAll(listOf("pause", "resume", "cancel"))
    }.ifEmpty { listOf("nothing: it only reports its state") }

    /** C20 §2 and D10: when the owner must approve [command] first. */
    fun approval(device: HomeDevice, command: DeviceCommand): Approval = when (command) {
        DeviceCommand.Unlock, DeviceCommand.Open -> Approval.ScreenOnly
        // Stopping a print for good can't be undone: always a card (C34).
        DeviceCommand.Close, DeviceCommand.CancelPrint, is DeviceCommand.SetMode -> Approval.Card
        is DeviceCommand.SetTemperature -> {
            val current = device.state?.setpoint
            val limit = device.capability<Capability.Thermostat>()?.bigChange ?: 5.0
            if (current == null || abs(command.value - current) > limit) Approval.Card else Approval.None
        }
        else -> Approval.None
    }

    /** "turn on", "set the brightness to 40%": for cards, status lines and the log. */
    fun label(command: DeviceCommand, unit: TempUnit? = null): String = when (command) {
        DeviceCommand.TurnOn -> "turn on"
        DeviceCommand.TurnOff -> "turn off"
        is DeviceCommand.SetBrightness -> "set the brightness to ${command.percent}%"
        is DeviceCommand.SetPercentage -> "set the speed to ${command.percent}%"
        is DeviceCommand.SetColor -> "set the colour to ${command.name}"
        is DeviceCommand.SetWhite -> "set it to ${command.name}"
        is DeviceCommand.SetTemperature -> "set it to ${fmt(command.value)}${unit?.symbol ?: "°"}"
        is DeviceCommand.SetMode -> "set the mode to ${command.mode}"
        DeviceCommand.Lock -> "lock"
        DeviceCommand.Unlock -> "unlock"
        DeviceCommand.Open -> "open"
        DeviceCommand.Close -> "close"
        DeviceCommand.Pause -> "pause the print"
        DeviceCommand.Resume -> "resume the print"
        DeviceCommand.CancelPrint -> "cancel the print"
    }

    /** The short name and value for the command log: ("set_temperature", "72"). */
    fun logParts(command: DeviceCommand): Pair<String, String?> = when (command) {
        DeviceCommand.TurnOn -> "turn_on" to null
        DeviceCommand.TurnOff -> "turn_off" to null
        is DeviceCommand.SetBrightness -> "set_brightness" to "${command.percent}%"
        is DeviceCommand.SetPercentage -> "set_percentage" to "${command.percent}%"
        is DeviceCommand.SetColor -> "set_color" to command.name
        is DeviceCommand.SetWhite -> "set_white" to command.name
        is DeviceCommand.SetTemperature -> "set_temperature" to fmt(command.value)
        is DeviceCommand.SetMode -> "set_thermostat_mode" to command.mode
        DeviceCommand.Lock -> "lock" to null
        DeviceCommand.Unlock -> "unlock" to null
        DeviceCommand.Open -> "open" to null
        DeviceCommand.Close -> "close" to null
        DeviceCommand.Pause -> "pause" to null
        DeviceCommand.Resume -> "resume" to null
        DeviceCommand.CancelPrint -> "cancel" to null
    }

    /** True when the state already shows [command] done: the re-check (C25 §6) stops early. */
    fun shows(state: DeviceState?, command: DeviceCommand): Boolean = state != null && when (command) {
        DeviceCommand.TurnOn -> state.on == true
        DeviceCommand.TurnOff -> state.on == false
        is DeviceCommand.SetBrightness -> state.brightness == command.percent
        is DeviceCommand.SetPercentage -> state.percentage == command.percent
        is DeviceCommand.SetColor -> state.color == command.name
        is DeviceCommand.SetWhite -> state.white == command.name
        is DeviceCommand.SetTemperature -> state.setpoint == command.value
        is DeviceCommand.SetMode -> state.mode == command.mode
        DeviceCommand.Lock -> state.lock == LockState.Locked
        DeviceCommand.Unlock -> state.lock == LockState.Unlocked
        DeviceCommand.Open -> state.door == DoorState.Open
        DeviceCommand.Close -> state.door == DoorState.Closed
        DeviceCommand.Pause -> state.print?.state == PrintState.Paused
        DeviceCommand.Resume -> state.print?.state == PrintState.Printing
        // Klipper says "cancelled" for a moment, then goes back to idle.
        DeviceCommand.CancelPrint -> state.print?.state == PrintState.Cancelled || state.print?.state == PrintState.Idle
    }

    /** Thermostats and locks (and garage doors) report late: they're re-checked longer (C25 §6). */
    fun slow(device: HomeDevice): Boolean =
        device.type in setOf(DeviceType.Thermostat, DeviceType.Lock, DeviceType.GarageDoor)

    /** What [state] says, in words: "On, 40%", "Heat to 70°F, 68°F now", "Locked". Empty when there's nothing. */
    fun stateText(device: HomeDevice, state: DeviceState? = device.state): String {
        if (state == null) return ""
        val unit = device.capability<Capability.Thermostat>()?.unit
        val parts = mutableListOf<String>()
        state.lock?.let { parts += it.label }
        state.door?.let { parts += it.label }
        state.on?.let { on ->
            parts += buildString {
                append(if (on) "On" else "Off")
                if (on) {
                    state.brightness?.let { append(", $it%") }
                    state.percentage?.let { append(", speed $it%") }
                    (state.color ?: state.white)?.let { append(", $it") }
                }
            }
        }
        if (state.mode == "off") {
            parts += "Off"
        } else if (state.mode != null || state.setpoint != null) {
            val mode = state.mode?.replaceFirstChar { it.titlecase(Locale.US) } ?: "Set"
            parts += mode + (state.setpoint?.let { " to ${fmt(it)}${unit?.symbol ?: "°"}" } ?: "")
        }
        state.print?.let { parts += printText(it) }
        if (state.heaters.isNotEmpty()) parts += state.heaters.joinToString(", ") { heatText(it) }
        state.temperature?.let { parts += "${fmt(it)}${unit?.symbol ?: "°"} now" }
        state.battery?.let { if (it <= 20) parts += "battery $it%" }
        return parts.joinToString(", ")
    }

    /** A print in words: "Printing Benchy, 42%, about 1 h 5 min left", "Paused Benchy, 42%", "Idle", "Error: Heater failed". */
    fun printText(job: PrintJob): String = buildString {
        append(job.state.label)
        val active = job.state == PrintState.Printing || job.state == PrintState.Paused
        job.title?.takeIf { job.state != PrintState.Idle }?.let { append(" $it") }
        if (active) append(", ${(job.progress * 100).toInt()}%")
        if (job.state == PrintState.Printing) job.timeLeftSeconds?.let { append(", about ${duration(it)} left") }
        if (active && job.layer != null && job.layers != null) append(", layer ${job.layer} of ${job.layers}")
        if (job.state == PrintState.Error || job.state == PrintState.Cancelled) job.message?.takeIf { it.isNotBlank() }?.let { append(": $it") }
    }

    /** "Nozzle 215/220°C", or "Bed 24°C" when it is off. */
    fun heatText(h: Heat): String = "${h.label} ${fmt(h.temperature)}" + if (h.target > 0) "/${fmt(h.target)}°C" else "°C"

    /** "1 h 5 min", "12 min", "under a minute". */
    fun duration(seconds: Long): String {
        val min = (seconds + 30) / 60
        return when {
            min < 1 -> "under a minute"
            min < 60 -> "$min min"
            min % 60 == 0L -> "${min / 60} h"
            else -> "${min / 60} h ${min % 60} min"
        }
    }

    /** "just now", "3 min ago", "2 h ago" (Jarvis's `ageOf`). */
    /** "moved 3 h ago": when the device says it last changed (a garage door's `changedAt`), or null. */
    fun movedText(state: DeviceState?, now: Long): String? = state?.changedAt?.takeIf { it > 0 && it <= now + 60_000 }?.let { "moved ${age(it, now)}" }

    fun age(at: Long, now: Long): String {
        val min = (now - at) / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${min / 60} h ago"
            else -> "${min / (24 * 60)} days ago"
        }
    }

    fun fmt(n: Double): String = if (n == n.roundToInt().toDouble()) n.roundToInt().toString() else String.format(Locale.US, "%.1f", n)

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
}

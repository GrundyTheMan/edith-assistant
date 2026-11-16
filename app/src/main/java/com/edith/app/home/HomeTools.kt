package com.edith.app.home

import com.edith.app.chat.engine.ConfirmationRequest
import com.edith.app.core.error.Outcome
import com.edith.app.tools.framework.ParamType
import com.edith.app.tools.framework.Tool
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolImage
import com.edith.app.tools.framework.ToolParam
import com.edith.app.tools.framework.ToolResult
import com.edith.app.tools.framework.ToolSpec

/** The smart-home tools (C25 §4, D19: Jarvis's shape, plus camera snapshots). */
fun homeTools(home: Home): List<Tool> = listOf(HomeDevicesTool(home), HomeControlTool(home), CameraSnapshotTool(home), RecentEventsTool(home), PrinterStatusTool(home))

/** [find] always explains a miss; this only covers the case it somehow did not. */
private const val NO_DEVICE = "I couldn't find that device."

private val DEVICE_PARAM = ToolParam("device", ParamType.String, "The device's name as home_devices lists it, e.g. Porch light", required = true,
    aliases = listOf("name", "device_name", "target"))

/** Finds [name] among the visible devices, or the words for the model about why not. */
private suspend fun find(home: Home, name: String?): Pair<HomeDevice?, String?> {
    if (name.isNullOrBlank()) return null to "Say which device (its name from home_devices)."
    val view = home.devicesForTools()
    return when (val m = DeviceNames.match(view.devices, name)) {
        is DeviceMatch.Found -> m.device to null
        else -> null to DeviceNames.problem(name, m) + view.problems.joinToString("") { "\n(${it.line})" }
    }
}

/** `home_devices(filter?)`: every device with what it's doing now, by room (Jarvis's `alexa_devices`). */
class HomeDevicesTool(private val home: Home, private val now: () -> Long = System::currentTimeMillis) : Tool {
    override val spec = ToolSpec(
        "home_devices",
        "The owner's smart-home devices (lights, plugs, fans, thermostats, locks, garage door, cameras, the 3D printer) and what each is doing now. " +
            "Pass filter to see only some, by name, room or type, e.g. \"kitchen\" or \"lock\".",
        listOf(ToolParam("filter", ParamType.String, "Only devices whose name, room or type contains this", aliases = listOf("room", "type", "query"))),
    )
    override val statusLabel = "Checking the house…"
    override val displayName = "home"
    override val integrationId = Home.ID

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val filter = args.string("filter")?.trim().orEmpty()
        val view = home.devicesForTools()
        val shown = if (filter.isEmpty()) view.devices else view.devices.filter { matches(it, filter) }
        return ToolResult.Ok(describe(shown, view, filter))
    }

    private fun matches(d: HomeDevice, filter: String): Boolean {
        val f = DeviceNames.norm(filter).removeSuffix("s")
        val text = DeviceNames.norm(listOfNotNull(d.name, d.room, d.type.label, d.type.name).joinToString(" "))
        return f.split(' ').all { it in text }
    }

    fun describe(devices: List<HomeDevice>, view: HomeView, filter: String): String {
        val lines = mutableListOf<String>()
        if (devices.isEmpty()) {
            lines += if (filter.isNotEmpty()) "No device matches \"$filter\"." else "No devices are listed."
        } else {
            val byRoom = devices.take(MAX_LISTED).groupBy { it.room ?: "No room" }
            for ((room, list) in byRoom) {
                lines += "$room:"
                for (d in list) lines += "- ${d.name} (${d.type.label}): ${stateForModel(d, view)}"
            }
            if (devices.size > MAX_LISTED) lines += "… and ${devices.size - MAX_LISTED} more; pass a filter to see them."
        }
        view.problems.forEach { lines += "(${it.line} Its devices show their last-known state.)" }
        return lines.joinToString("\n")
    }

    private fun stateForModel(d: HomeDevice, view: HomeView): String {
        if (!d.online) return if (d.type == DeviceType.Printer) "not reachable (it works on the home Wi-Fi, or it is off)" else "not responding"
        val text = HomeActions.stateText(d).ifEmpty { "no state reported" }.let { t -> HomeActions.movedText(d.state, now())?.let { "$t, last $it" } ?: t }
        val at = d.stateAt
        return if (d.backend in view.stale && at != null) "$text (last reported ${HomeActions.age(at, now())}; no answer just now)" else text
    }

    companion object {
        /** Devices sent to the model at once, so a big home doesn't fill the context (as Jarvis). */
        const val MAX_LISTED = 80
    }
}

/** `home_control(device, action, value?)`: acts on one device (Jarvis's `alexa_control`), with C20 §2's cards. */
class HomeControlTool(private val home: Home) : Tool {
    override val spec = ToolSpec(
        "home_control",
        "Control one smart-home device by its exact name from home_devices. Unlocking and opening the garage show the owner a card to tap.",
        listOf(
            DEVICE_PARAM,
            ToolParam("action", ParamType.String, "What to do", required = true, enumValues = HomeActions.NAMES, aliases = listOf("command")),
            ToolParam("value", ParamType.String,
                "For set_brightness and set_percentage (fan speed: low 33, medium 66, high 100) a percentage 0-100; set_temperature a number (°F in the US, 50-90°F or 10-32°C); " +
                    "set_thermostat_mode heat, cool, auto or off; set_color a colour name; set_white warm white, soft white, daylight white or cool white",
                aliases = listOf("level", "temperature", "mode", "color", "percent")),
        ),
    )
    override val statusLabel = "Working on it…"
    override val displayName = "home"
    override val integrationId = Home.ID
    override val guidance = "For a home action, call home_control straight away; don't ask the owner to confirm in chat: the app shows a card when one is needed."

    override fun statusLabelFor(args: ToolArgs): String {
        val device = args.string("device") ?: return statusLabel
        return when (args.string("action")) {
            "turn_on" -> "Turning on $device…"
            "turn_off" -> "Turning off $device…"
            "lock" -> "Locking $device…"
            "unlock" -> "Unlocking $device…"
            "open" -> "Opening $device…"
            "close" -> "Closing $device…"
            "pause" -> "Pausing $device…"
            "resume" -> "Resuming $device…"
            "cancel" -> "Cancelling $device…"
            else -> "Adjusting $device…"
        }
    }

    private suspend fun prepare(args: ToolArgs): Prepared {
        val (device, problem) = find(home, args.string("device"))
        if (device == null) return Prepared.Stop(problem ?: NO_DEVICE)
        val action = args.string("action")?.trim().orEmpty()
        val command = HomeActions.parse(device, action, args.string("value")).getOrElse { return Prepared.Stop("${it.message}.") }
        return Prepared.Ready(device, command)
    }

    private sealed interface Prepared {
        data class Ready(val device: HomeDevice, val command: DeviceCommand) : Prepared
        data class Stop(val why: String) : Prepared
    }

    override suspend fun confirmation(args: ToolArgs): ConfirmationRequest? {
        val p = prepare(args) as? Prepared.Ready ?: return null   // execute explains
        return card(p.device, p.command)
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult = when (val p = prepare(args)) {
        is Prepared.Stop -> ToolResult.Error(p.why)
        is Prepared.Ready -> when (val r = home.command(p.device, p.command, CommandSource.Chat)) {
            is Outcome.Success -> {
                val now = HomeActions.stateText(p.device, r.value)
                val late = if (HomeActions.slow(p.device) && !HomeActions.shows(r.value, p.command)) {
                    " It may take a few seconds to report the change; the Home tab updates when it does."
                } else ""
                ToolResult.Ok("Done: ${HomeActions.label(p.command, unit(p.device))} — ${p.device.name}." +
                    (if (now.isNotEmpty()) " It now reports: $now." else "") + late)
            }
            is Outcome.Failure -> ToolResult.Error("${p.device.name} didn't do it: ${Home.why(r.error)}.")
        }
    }

    companion object {
        /** The card for [command], or null when it needs none (C20 §2). The Home tab's dialog uses the same words. */
        fun card(device: HomeDevice, command: DeviceCommand): ConfirmationRequest? {
            val approval = HomeActions.approval(device, command)
            if (approval == Approval.None) return null
            val what = HomeActions.label(command, unit(device))
            val details = listOfNotNull(
                "Device" to device.name + (device.room?.let { " ($it)" } ?: ""),
                HomeActions.stateText(device).takeIf { it.isNotEmpty() }?.let { "Now" to it },
                if (approval == Approval.ScreenOnly) "Note" to "Needs a tap on the screen; a voice can't approve this." else null,
            )
            return ConfirmationRequest(
                title = "${what.replaceFirstChar { it.uppercase() }}: ${device.name}?",
                details = details,
                confirmLabel = if (command == DeviceCommand.CancelPrint) "Cancel print" else what.substringBefore(' ').replaceFirstChar { it.uppercase() }.let { if (it == "Set" || it == "Turn") "Confirm" else it },
                // "Cancel" on both buttons would mean "stop the print" on one and "keep printing" on the other.
                denyLabel = if (command == DeviceCommand.CancelPrint) "Keep printing" else "Cancel",
                screenOnly = approval == Approval.ScreenOnly,
            )
        }

        private fun unit(d: HomeDevice) = d.capability<Capability.Thermostat>()?.unit
    }
}

/** `camera_snapshot(device)`: a camera's picture, shown in Edith's bubble (C25 §4, C20 §2). */
class CameraSnapshotTool(private val home: Home) : Tool {
    override val spec = ToolSpec(
        "camera_snapshot",
        "Take a picture from a camera or doorbell. The owner sees it in your reply.",
        listOf(DEVICE_PARAM),
    )
    override val statusLabel = "Getting a picture…"
    override val displayName = "camera"
    override val integrationId = Home.ID

    override fun statusLabelFor(args: ToolArgs): String = args.string("device")?.let { "Getting a picture from $it…" } ?: statusLabel

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val (device, problem) = find(home, args.string("device"))
        if (device == null) return ToolResult.Error(problem ?: NO_DEVICE)
        if (!device.has(Capability.Snapshot)) return ToolResult.Error("${device.name} isn't a camera.")
        return when (val r = home.snapshot(device)) {
            is Outcome.Success -> ToolResult.Ok(
                "Here's the picture from ${device.name}. The owner sees it with your reply.",
                images = listOf(ToolImage("${device.name}.jpg", r.value)),
            )
            is Outcome.Failure -> ToolResult.Error("No picture from ${device.name}: ${Home.why(r.error)}.")
        }
    }
}

/** `recent_events(device?, hours?)`: what the doorbells and cameras reported (rings, motion, people), newest first (C27). */
class RecentEventsTool(private val home: Home) : Tool {
    override val spec = ToolSpec(
        "recent_events",
        "What the owner's doorbells and cameras reported lately: doorbell rings, motion and people, newest first. " +
            "Use it for \"did anyone ring today?\" or \"was there motion at the front door?\". Pass device to ask about one camera; hours defaults to 24.",
        listOf(
            ToolParam("device", ParamType.String, "One camera or doorbell's name as home_devices lists it; leave out for all of them", aliases = listOf("name", "camera")),
            ToolParam("hours", ParamType.Integer, "How far back to look, 1-72 (default 24)", aliases = listOf("since_hours", "period")),
        ),
    )
    override val statusLabel = "Checking the cameras…"
    override val displayName = "camera"
    override val integrationId = Home.ID

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val hours = (args.int("hours") ?: DEFAULT_HOURS).coerceIn(1, MAX_HOURS)
        val view = home.devicesForTools()
        val named = args.string("device")?.takeIf { it.isNotBlank() }
        val cameras = if (named != null) {
            val (device, problem) = find(home, named)
            if (device == null) return ToolResult.Error(problem!!)
            if (!device.has(Capability.Events)) return ToolResult.Error("${device.name} doesn't report events.")
            listOf(device)
        } else view.devices.filter { it.has(Capability.Events) }
        if (cameras.isEmpty()) return ToolResult.Ok("No doorbell or camera that reports events is connected.")
        val now = ctx.clock.now().toEpochMilli()
        val since = now - hours * 3_600_000L
        val zone = ctx.clock.zone()
        val lines = mutableListOf<String>()
        for (d in cameras) {
            when (val r = home.events(d, since)) {
                is Outcome.Failure -> lines += "${d.name}: couldn't get its events (${Home.why(r.error)})."
                is Outcome.Success -> {
                    val shown = r.value.filter { it.kind != EventKind.Other }.take(MAX_PER_CAMERA)
                    if (shown.isEmpty()) lines += "${d.name}: nothing reported in the last $hours hours."
                    else {
                        lines += "${d.name}:"
                        shown.forEach { lines += "- ${it.kind.label} at ${TIME.format(java.time.Instant.ofEpochMilli(it.at).atZone(zone))} (${HomeActions.age(it.at, now)})" }
                    }
                }
            }
        }
        return ToolResult.Ok(lines.joinToString("\n"))
    }

    companion object {
        const val DEFAULT_HOURS = 24
        const val MAX_HOURS = 72
        const val MAX_PER_CAMERA = 20
        private val TIME = java.time.format.DateTimeFormatter.ofPattern("EEE h:mm a", java.util.Locale.US)
    }
}

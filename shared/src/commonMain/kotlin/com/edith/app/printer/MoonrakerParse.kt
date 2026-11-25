package com.edith.app.printer

import kotlin.math.roundToLong
import com.edith.app.home.DeviceState
import com.edith.app.home.Heat
import com.edith.app.home.PrintJob
import com.edith.app.home.PrintState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import io.ktor.http.decodeURLQueryComponent

/** What Klipper itself is doing: Moonraker can answer while Klipper is starting or has shut down. */
enum class Klippy { Ready, Startup, Shutdown, Error }

/** A printer's status as read: what to show, and the file being printed (to look up its estimated time). */
data class PrinterStatus(val klippy: Klippy, val state: DeviceState, val filename: String?, val printSeconds: Double)

/** A webcam Moonraker lists: where to get a picture. */
data class PrinterCamera(val name: String, val snapshotUrl: String)

/**
 * Parsing what Moonraker answers (Jarvis's `shared/printer.ts`, the parts C34 shows). It reads defensively and never
 * throws on an answer it doesn't understand: a printer's firmware (ZMOD, stock Klipper) reports more or less.
 */
object MoonrakerParse {
    /** The Klipper objects asked for, as the query string of `/printer/objects/query`. Objects a printer lacks are left out of the answer. */
    const val OBJECTS = "webhooks&print_stats&display_status&virtual_sdcard&pause_resume&idle_timeout&extruder&heater_bed"

    private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    /** `{"result": {"status": {...}}}`'s status; an empty object when it isn't there. */
    fun statusOf(result: JsonElement?): JsonObject = result.obj()["status"].obj()

    /**
     * The printer as [raw] (the `status` of an objects query) shows it. [estimatedSeconds] is the slicer's total estimate for the file,
     * when Moonraker has it (`/server/files/metadata`).
     */
    fun status(raw: JsonObject, estimatedSeconds: Double? = null): PrinterStatus {
        val webhooks = raw["webhooks"].obj()
        val klippy = when (webhooks["state"].text()) {
            null, "ready" -> Klippy.Ready
            "startup" -> Klippy.Startup
            "shutdown" -> Klippy.Shutdown
            else -> Klippy.Error
        }
        val stats = raw["print_stats"].obj()
        val filename = stats["filename"].text()
        val printSeconds = stats["print_duration"].number() ?: 0.0
        val heaters = heaters(raw)
        if (klippy != Klippy.Ready) {
            val why = webhooks["state_message"].text()?.lineSequence()?.firstOrNull()?.trim()
            val message = when (klippy) {
                Klippy.Startup -> "Klipper is starting up"
                Klippy.Shutdown -> why ?: "Klipper has shut down"
                else -> why ?: "Klipper isn't ready"
            }
            return PrinterStatus(klippy, DeviceState(print = PrintJob(PrintState.Error, message = message)), filename, printSeconds)
        }
        val state = when (stats["state"].text()) {
            "printing" -> PrintState.Printing
            "paused" -> PrintState.Paused
            "complete" -> PrintState.Complete
            "cancelled" -> PrintState.Cancelled
            "error" -> PrintState.Error
            else -> PrintState.Idle
        }
        // display_status.progress is the file's M73 (the slicer's progress by time) while it prints, else the file position again.
        val filePos = raw["virtual_sdcard"].obj()["progress"].number() ?: raw["display_status"].obj()["progress"].number() ?: 0.0
        val shown = raw["display_status"].obj()["progress"].number() ?: filePos
        val byTime = shown > 0 && kotlin.math.abs(shown - filePos) > 1e-6
        val progress = (if (byTime) shown else filePos).coerceIn(0.0, 1.0)
        val info = stats["info"].obj()
        val layers = info["total_layer"].number()?.toInt()?.takeIf { it > 0 }
        val job = PrintJob(
            state = state,
            title = filename?.let(::fileTitle),
            progress = progress,
            timeLeftSeconds = if (state == PrintState.Printing || state == PrintState.Paused) timeLeft(progress, printSeconds, estimatedSeconds, byTime) else null,
            layer = if (layers != null) info["current_layer"].number()?.toInt() else null,
            layers = layers,
            message = stats["message"].text(),
        )
        return PrinterStatus(klippy, DeviceState(print = job, heaters = heaters), filename, printSeconds)
    }

    /** The nozzle first, then the bed (a printer with more heaters can add them later). */
    private fun heaters(raw: JsonObject): List<Heat> = buildList {
        for ((id, label) in listOf("extruder" to "Nozzle", "heater_bed" to "Bed")) {
            val h = raw[id] as? JsonObject ?: continue
            val t = h["temperature"].number() ?: continue
            add(Heat(label, t, h["target"].number() ?: 0.0))
        }
    }

    /**
     * Seconds left (Jarvis's `timeLeft`). With the slicer's total estimate: the part of it still to print when [byTime]
     * (the progress is the slicer's own, from M73, which is what the printer's screen shows), else the total minus time
     * printed. Without one: time printed divided by progress, less time printed, and null too early to say.
     */
    fun timeLeft(progress: Double, printSeconds: Double, estimatedSeconds: Double?, byTime: Boolean): Long? {
        val p = progress.coerceIn(0.0, 1.0)
        if (estimatedSeconds != null && estimatedSeconds > 0) {
            return (maxOf(0.0, if (byTime) estimatedSeconds * (1 - p) else estimatedSeconds - printSeconds)).roundToLong()
        }
        if (p < ESTIMATE_FROM_PROGRESS || printSeconds < ESTIMATE_FROM_SECONDS) return null
        return (maxOf(0.0, printSeconds / p - printSeconds)).roundToLong()
    }

    private const val ESTIMATE_FROM_PROGRESS = 0.01
    private const val ESTIMATE_FROM_SECONDS = 60.0

    /** The slicer's total estimate in a file's metadata (`/server/files/metadata`'s `estimated_time`), in seconds. */
    fun estimatedTime(metadata: JsonElement?): Double? = metadata.obj()["estimated_time"].number()?.takeIf { it > 0 }

    /**
     * A file's name for people (Jarvis's `fileTitle`): a Bambu-style plate is "3mf/model/Chimney+and+Platforms_plate_1/Metadata/plate_1.gcode",
     * which becomes "Chimney and Platforms (plate 1)". Other paths lose their folder and extension.
     */
    fun fileTitle(path: String): String {
        Regex("^3mf/model/(.+?)/Metadata/plate_(\\d+)\\.gcode$", RegexOption.IGNORE_CASE).find(path)?.let { m ->
            val plate = m.groupValues[2]
            return "${unescape(m.groupValues[1].removeSuffix("_plate_$plate")).trim()} (plate $plate)"
        }
        val base = path.substringAfterLast('/')
        return unescape(base.replace(Regex("\\.(gcode|g|gco|3mf|ufp)$", RegexOption.IGNORE_CASE), "")).trim().ifEmpty { path }
    }

    private fun unescape(s: String): String = try { s.decodeURLQueryComponent(plusIsSpace = true) } catch (e: Exception) { s.replace('+', ' ') }

    /**
     * The webcams `/server/webcams/list` gives, with a snapshot address each. Moonraker's addresses are often relative
     * ("/webcam/?action=snapshot"), made absolute against [urls]'s web address. Disabled webcams and ones with no snapshot are left out.
     */
    fun cameras(result: JsonElement?, urls: PrinterUrls): List<PrinterCamera> {
        val list = result.obj()["webcams"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { raw ->
            val w = raw.obj()
            if ((w["enabled"] as? JsonPrimitive)?.contentOrNull == "false") return@mapNotNull null
            val snapshot = w["snapshot_url"].text() ?: return@mapNotNull null
            PrinterCamera(w["name"].text() ?: "Camera", absolute(urls, snapshot))
        }
    }

    private fun absolute(urls: PrinterUrls, url: String): String =
        if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) url else urls.web + (if (url.startsWith("/")) "" else "/") + url

}

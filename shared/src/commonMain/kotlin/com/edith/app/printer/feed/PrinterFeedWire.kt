package com.edith.app.printer.feed

import kotlinx.serialization.Serializable

/**
 * The wire types of the printer part of Jarvis's feed (C39; contract: `docs/design/jarvis-printer-feed-spec.md`).
 * The shapes are Jarvis's own TypeScript (`src/shared/printer.ts`), with the same field names. Every field has a
 * default so a field a newer or older Jarvis leaves out never breaks the parse, and every value Jarvis keeps as a
 * string union (the print state, the connection, a risk) stays a `String` here: compare with [PrinterStates] and
 * friends, so a new value is shown, not thrown on. Parse with `feedJson` (unknown fields ignored).
 */

/** Values of the string unions, so a typo can't compile into a wrong comparison. */
object PrinterStates {
    const val STANDBY = "standby"
    const val PRINTING = "printing"
    const val PAUSED = "paused"
    const val COMPLETE = "complete"
    const val CANCELLED = "cancelled"
    const val ERROR = "error"
}

object Connections {
    const val NONE = "none"
    const val CONNECTING = "connecting"
    const val READY = "ready"
    const val KLIPPY_STARTUP = "klippy-startup"
    const val KLIPPY_SHUTDOWN = "klippy-shutdown"
    const val KLIPPY_ERROR = "klippy-error"
    const val UNREACHABLE = "unreachable"
}

object Risks {
    const val NONE = "none"
    const val CONFIRM = "confirm"
    const val DANGER = "danger"
}

@Serializable data class Layer(val current: Int = 0, val total: Int = 0)

@Serializable data class PrintInfo(
    val state: String = PrinterStates.STANDBY,
    val filename: String? = null,
    val title: String? = null,
    val progress: Double = 0.0,
    val printDuration: Double = 0.0,
    val totalDuration: Double = 0.0,
    val timeLeft: Double? = null,
    val timeLeftFrom: String? = null,
    val filamentUsed: Double = 0.0,
    val layer: Layer? = null,
    val message: String? = null,
    val paused: Boolean = false,
    val busy: Boolean = false,
)

@Serializable data class Heater(
    val id: String = "",
    val label: String = "",
    val temperature: Double = 0.0,
    val target: Double = 0.0,
    val power: Double = 0.0,
    val maxTemp: Double = 0.0,
)

@Serializable data class Sensor(val id: String = "", val label: String = "", val value: Double = 0.0, val unit: String = "")

@Serializable data class Fan(val id: String = "", val label: String = "", val speed: Double = 0.0, val settable: Boolean = false)

@Serializable data class FilamentSensor(
    val id: String = "",
    val label: String = "",
    val detected: Boolean = false,
    val enabled: Boolean = false,
)

@Serializable data class FilamentSlot(
    val id: String = "",
    val material: String = "",
    val color: String = "",
    val hex: String = "",
    val loaded: Boolean = false,
)

@Serializable data class PrintObject(
    val name: String = "",
    val center: List<Double>? = null,
    val polygon: List<List<Double>>? = null,
    val excluded: Boolean = false,
    val current: Boolean = false,
)

/** A camera Jarvis lists. The picture URLs never leave the PC: the phone asks Jarvis for the bytes by id. */
@Serializable data class PrinterCamera(
    val id: String = "",
    val name: String = "",
    val aspect: Double = 4.0 / 3.0,
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
)

@Serializable data class Toolhead(
    val position: List<Double> = listOf(0.0, 0.0, 0.0),
    val homed: String = "",
    val min: List<Double> = listOf(0.0, 0.0, 0.0),
    val max: List<Double> = listOf(0.0, 0.0, 0.0),
    val liveVelocity: Double = 0.0,
    val maxVelocity: Double = 0.0,
    val maxAccel: Double = 0.0,
    val squareCornerVelocity: Double = 0.0,
    val minimumCruiseRatio: Double = 0.0,
    val speedFactor: Double = 1.0,
    val extrudeFactor: Double = 1.0,
    val zOffset: Double = 0.0,
    val motorsOn: Boolean = false,
)

@Serializable data class ExtruderInfo(
    val canExtrude: Boolean = false,
    val pressureAdvance: Double? = null,
    val minExtrudeTemp: Double = 0.0,
    val nozzleDiameter: Double? = null,
)

@Serializable data class Retraction(
    val length: Double = 0.0,
    val speed: Double = 0.0,
    val extraLength: Double = 0.0,
    val unretractSpeed: Double = 0.0,
)

@Serializable data class PrinterAlert(val text: String = "", val time: Long = 0)

@Serializable data class HostInfo(val load: Double = 0.0, val memAvailableKb: Double = 0.0)

@Serializable data class Versions(val moonraker: String? = null, val mcu: String? = null)

@Serializable data class BedMesh(
    val profile: String = "",
    val active: Boolean = false,
    val min: List<Double> = listOf(0.0, 0.0),
    val max: List<Double> = listOf(0.0, 0.0),
    val points: List<List<Double>> = emptyList(),
)

@Serializable data class QueueJob(val id: String = "", val filename: String = "", val title: String = "", val added: Long = 0)

@Serializable data class JobQueue(val state: String = "ready", val jobs: List<QueueJob> = emptyList())

/** Everything the Printer page shows (`PrinterState` in Jarvis). */
@Serializable data class PrinterState(
    val connection: String = Connections.NONE,
    val message: String? = null,
    val lastSeen: Long? = null,
    val hostname: String? = null,
    val print: PrintInfo = PrintInfo(),
    val heaters: List<Heater> = emptyList(),
    val sensors: List<Sensor> = emptyList(),
    val fans: List<Fan> = emptyList(),
    val light: Double? = null,
    val toolhead: Toolhead = Toolhead(),
    val extruder: ExtruderInfo = ExtruderInfo(),
    val retraction: Retraction? = null,
    val filamentSensors: List<FilamentSensor> = emptyList(),
    val objects: List<PrintObject> = emptyList(),
    val slots: List<FilamentSlot> = emptyList(),
    val slotsLive: Boolean = false,
    val alert: PrinterAlert? = null,
    val host: HostInfo? = null,
    val versions: Versions? = null,
    val cameras: List<PrinterCamera> = emptyList(),
    val mesh: BedMesh? = null,
    val queue: JobQueue? = null,
) {
    val isReady get() = connection == Connections.READY
}

val PrintInfo.isActive get() = state == PrinterStates.PRINTING || state == PrinterStates.PAUSED

@Serializable data class ArmState(val armed: Boolean = false, val until: Long? = null)

@Serializable data class QuickCheck(val allowed: Boolean = false, val reason: String? = null)

/** The `state` route's answer (a long poll). `rev` unchanged means "nothing new"; a changed `epoch` means Jarvis restarted. */
@Serializable data class StateAnswer(
    val rev: Long = 0,
    val epoch: Long = 0,
    val state: PrinterState = PrinterState(),
    val arm: ArmState = ArmState(),
    val quick: Map<String, QuickCheck> = emptyMap(),
    val consoleSeq: Long = 0,
    val serverTime: Long = 0,
)

/** The check of a command: the dry run's answer, and the body of `CONFIRM_REQUIRED`. */
@Serializable data class Check(
    val allowed: Boolean = false,
    val risk: String = Risks.NONE,
    val reason: String? = null,
    val question: String? = null,
    val confirm: String? = null,
)

/** `{ "sent": "G28", "risk": "confirm" }` */
@Serializable data class CommandSent(val sent: String = "", val risk: String = Risks.NONE)

@Serializable data class ControlsAnswer(val arm: ArmState = ArmState())

@Serializable data class Preset(val name: String = "", val extruder: Int = 0, val bed: Int = 0)

@Serializable data class CameraRef(val id: String = "", val name: String = "")

/** `GET /v1/shared` -> `printer`: absent means the printer is not shared. */
@Serializable data class SharedPrinter(
    val name: String = "",
    val control: Boolean = false,
    val alerts: Boolean = false,
    val armMinutes: Int = 2,
    val presets: List<Preset> = emptyList(),
    val cameras: List<CameraRef> = emptyList(),
    val features: List<String> = emptyList(),
    val schema: Int = 1,
)

@Serializable data class PrinterFile(
    val path: String = "",
    val size: Long = 0,
    val modified: Long = 0,
    val title: String = "",
    val kind: String = "other",
    val printedBefore: Boolean = false,
)

@Serializable data class FilesAnswer(val files: List<PrinterFile> = emptyList(), val cachedAt: Long = 0)

@Serializable data class PrinterFileDetail(
    val path: String = "",
    val estimatedTime: Double? = null,
    val filamentTotal: Double? = null,
    val layerCount: Int? = null,
    val thumbnail: String? = null,
)

@Serializable data class HistoryJob(
    val id: String = "",
    val filename: String = "",
    val title: String = "",
    val status: String = "",
    val start: Long = 0,
    val end: Long? = null,
    val printDuration: Double = 0.0,
    val filamentUsed: Double = 0.0,
)

@Serializable data class HistoryTotals(
    val jobs: Int = 0,
    val printTime: Double = 0.0,
    val filamentUsed: Double = 0.0,
    val longestPrint: Double = 0.0,
)

@Serializable data class HistoryAnswer(val jobs: List<HistoryJob> = emptyList(), val totals: HistoryTotals = HistoryTotals())

/** A line of the printer's console; `seq` numbers the lines so the phone asks only for the new ones. */
@Serializable data class ConsoleLine(val seq: Long = 0, val time: Long = 0, val text: String = "", val kind: String = "response")

@Serializable data class ConsoleAnswer(val lines: List<ConsoleLine> = emptyList(), val seq: Long = 0)

@Serializable data class TempSeries(
    val id: String = "",
    val label: String = "",
    val temperatures: List<Double> = emptyList(),
    val targets: List<Double>? = null,
)

@Serializable data class TempAnswer(val series: List<TempSeries> = emptyList())

@Serializable data class MacroParam(val name: String = "", val default: String? = null)

@Serializable data class MacroInfo(
    val name: String = "",
    val description: String? = null,
    val params: List<MacroParam> = emptyList(),
    val risk: String = Risks.CONFIRM,
    val group: String = "other",
)

@Serializable data class MacroGroup(val id: String = "", val title: String = "")

@Serializable data class MacrosAnswer(
    val macros: List<MacroInfo> = emptyList(),
    val groups: List<MacroGroup> = emptyList(),
    val hidden: List<String> = emptyList(),
)

@Serializable data class FeedEvent(
    val seq: Long = 0,
    val time: Long = 0,
    val kind: String = "",
    val title: String = "",
    val text: String = "",
)

@Serializable data class EventsAnswer(val seq: Long = 0, val events: List<FeedEvent> = emptyList())

@Serializable data class UploadAnswer(val path: String = "")

/** `POST settings` answers what is now saved. */
@Serializable data class SettingsAnswer(val presets: List<Preset> = emptyList(), val hiddenMacros: List<String> = emptyList())

@Serializable data class RecordingJob(
    val id: String = "",
    val filename: String = "",
    val status: String = "",
    val printDuration: Double = 0.0,
)

@Serializable data class Recording(
    val path: String = "",
    val name: String = "",
    val title: String = "",
    val size: Long = 0,
    val time: Long = 0,
    val thumbnail: String? = null,
    val job: RecordingJob? = null,
    val frames: Int? = null,
    val duration: Double? = null,
)

@Serializable data class RecordingList(
    val folders: List<String> = emptyList(),
    val recordings: List<Recording> = emptyList(),
    val sshError: String? = null,
)

@Serializable data class RecordingInfo(val thumbnail: String? = null, val duration: Double? = null)

/** The error body of a refused command that needs the owner's yes: `{ "error": {…}, "check": {…} }`. */
@Serializable data class CheckErrorBody(val check: Check = Check())

package com.edith.app.home

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.tools.framework.Integration

/**
 * The smart home, independent of any service (C20 §1, widened for Jarvis parity in C25/D19). Every backend (Alexa,
 * Ring, Resideo, …) maps its devices onto these types; the tools and the Home tab only see these.
 */
enum class DeviceType(val label: String) {
    Light("Light"), Switch("Switch"), Plug("Plug"), Fan("Fan"), Thermostat("Thermostat"),
    TemperatureSensor("Temperature sensor"), Lock("Lock"), GarageDoor("Garage door"), Camera("Camera"),
    Doorbell("Doorbell"), SecurityPanel("Security panel"), Sensor("Sensor"), Speaker("Speaker"), Printer("3D printer"), Other("Device"),
}

/** A thermostat's temperature scale and its symbol. */
enum class TempUnit(val symbol: String) { Fahrenheit("°F"), Celsius("°C") }

/** What a device can do. A tool or tile offers an action only when the device has the capability for it. */
sealed interface Capability {
    data object OnOff : Capability
    data object Brightness : Capability
    /** Fans: a speed as a percentage. */
    data object Percentage : Capability
    data class Color(val names: List<String> = DEFAULT_COLORS) : Capability
    data class WhiteTone(val names: List<String> = DEFAULT_WHITES) : Capability
    data class Thermostat(val modes: List<String>, val unit: TempUnit) : Capability {
        val min: Double get() = if (unit == TempUnit.Fahrenheit) 50.0 else 10.0
        val max: Double get() = if (unit == TempUnit.Fahrenheit) 90.0 else 32.0
        /** C20 §2: a bigger setpoint change than this needs a card. */
        val bigChange: Double get() = if (unit == TempUnit.Fahrenheit) 5.0 else 3.0
    }
    data object LockUnlock : Capability
    data object OpenClose : Capability
    data object Snapshot : Capability
    data object Events : Capability
    /** Live video (C32): the Home tab shows a Live button. */
    data object Live : Capability
    /** A 3D printer's job: pause, resume and cancel (C34). */
    data object PrintControl : Capability

    companion object {
        val DEFAULT_COLORS = listOf("red", "orange", "yellow", "green", "blue", "purple", "pink", "white")
        /** Alexa's white tones (Jarvis's `set_white`). */
        val DEFAULT_WHITES = listOf("warm white", "soft white", "daylight white", "cool white")
    }
}

/** A lock's state, in the words the Home tab shows. */
enum class LockState(val label: String) { Locked("Locked"), Unlocked("Unlocked"), Jammed("Jammed") }
/** A garage door's state, in the words the Home tab shows. */
enum class DoorState(val label: String) { Open("Open"), Closed("Closed"), Opening("Opening"), Closing("Closing") }

/** What a device reports now. Every field is optional: a device reports only what it has (Jarvis's `StateValues`). */
data class DeviceState(
    val on: Boolean? = null,
    val brightness: Int? = null,
    val percentage: Int? = null,
    val color: String? = null,
    val white: String? = null,
    /** The temperature it measures, in the thermostat's unit. */
    val temperature: Double? = null,
    val setpoint: Double? = null,
    val mode: String? = null,
    val lock: LockState? = null,
    val door: DoorState? = null,
    val battery: Int? = null,
    /** When it last changed or moved (ms since 1970), if the device says: a garage door's "last moved". */
    val changedAt: Long? = null,
    /** A 3D printer's job (C34). */
    val print: PrintJob? = null,
    /** A printer's heaters, nozzle first (C34). */
    val heaters: List<Heat> = emptyList(),
)


/** Where a 3D printer's job stands, as Klipper's `print_stats.state` reports it (C34). */
enum class PrintState(val label: String) {
    Idle("Idle"), Printing("Printing"), Paused("Paused"), Complete("Complete"), Cancelled("Cancelled"), Error("Error"),
}

/** One 3D printer job (C34). [progress] is 0 to 1; [timeLeftSeconds] is null when it can't be estimated yet. */
data class PrintJob(
    val state: PrintState,
    /** For people: "Benchy", "Chimney and Platforms (plate 1)". */
    val title: String? = null,
    val progress: Double = 0.0,
    val timeLeftSeconds: Long? = null,
    val layer: Int? = null,
    val layers: Int? = null,
    /** Klipper's message: an error, or why the printer isn't ready. */
    val message: String? = null,
    /** The state came through Jarvis on the PC, because the printer did not answer directly (C39). */
    val viaJarvis: Boolean = false,
)

/** A heater's reading: [target] is 0 when it is off. */
data class Heat(val label: String, val temperature: Double, val target: Double)

/** One smart-home device as the Home tab and Edith's tools see it (C25). */
data class HomeDevice(
    /** The backend's own id; unique within [backend]. */
    val id: String,
    val name: String,
    val room: String?,
    val type: DeviceType,
    val capabilities: Set<Capability>,
    /** The backend's [HomeBackend.id]. */
    val backend: String,
    val online: Boolean = true,
    val state: DeviceState? = null,
    /** When [state] was reported (ms since 1970); null when it's unknown. */
    val stateAt: Long? = null,
) {
    /** Unique across backends: favourites, hidden devices and the command log use it. */
    val key: String get() = "$backend:$id"

    inline fun <reified C : Capability> capability(): C? = capabilities.filterIsInstance<C>().firstOrNull()
    fun has(c: Capability): Boolean = c in capabilities
}

/** One command (C25 §1). The tool's `action` names map onto these one to one. */
sealed interface DeviceCommand {
    data object TurnOn : DeviceCommand
    data object TurnOff : DeviceCommand
    data class SetBrightness(val percent: Int) : DeviceCommand
    data class SetPercentage(val percent: Int) : DeviceCommand
    data class SetColor(val name: String) : DeviceCommand
    data class SetWhite(val name: String) : DeviceCommand
    data class SetTemperature(val value: Double) : DeviceCommand
    data class SetMode(val mode: String) : DeviceCommand
    data object Lock : DeviceCommand
    data object Unlock : DeviceCommand
    data object Open : DeviceCommand
    data object Close : DeviceCommand
    /** A print (C34): pause it, carry on, or stop it for good. */
    data object Pause : DeviceCommand
    data object Resume : DeviceCommand
    data object CancelPrint : DeviceCommand
}

/** What made a camera or doorbell report (C27): Ring's `ding`, motion, a person, or the owner's own live view. */
enum class EventKind(val label: String) {
    Ring("Someone rang the doorbell"), Motion("Motion"), Person("A person"), LiveView("Live view"), Other("Activity"),
}

/** One thing a camera or doorbell reported, [at] ms since 1970. */
data class DeviceEvent(val at: Long, val kind: EventKind)

/** One recorded clip (C27): [backend] is filled in by [Home]; [id] is the service's own. */
data class DeviceClip(
    val id: String,
    val backend: String,
    val deviceName: String,
    val at: Long,
    val kind: EventKind,
    val seconds: Int?,
)

/** Where a clip plays from: an address, and headers the player must send with it (Blink's clips need its sign-in; Ring's addresses are signed). */
data class ClipSource(val url: String, val headers: Map<String, String> = emptyMap())

/**
 * Live video from a camera (C32). [ended] is why it ended, null while it runs; [stop] ends it and tells the service, so a camera
 * isn't left streaming. Video arrives one of two ways: [StreamLiveFeed] (Blink) or [TrackLiveFeed] (Ring, WebRTC).
 */
interface LiveFeed {
    val ended: kotlinx.coroutines.flow.StateFlow<String?>
    fun stop()
}

/** Video as MPEG transport stream bytes in [video]; the channel closes when the view ends (Blink). */
interface StreamLiveFeed : LiveFeed {
    val video: kotlinx.coroutines.channels.ReceiveChannel<ByteArray>
}

/** A window of clips. [noPlan]: the service has recordings only with a paid plan, and this account has none (Ring Protect). */
data class ClipsPage(val clips: List<DeviceClip>, val noPlan: Boolean = false)

/**
 * One smart-home service (C20 §1): Alexa, Ring, Resideo, … Each lives in its own chunk (C26–C31) and fails on its own.
 * Every call returns an [Outcome]; a backend never throws for a service's refusal.
 */
interface HomeBackend : Integration {
    /** Shown in Settings → Home and in problem lines ("Alexa didn't answer"). */
    val displayName: String
    /**
     * A hub (Alexa) reports devices that a direct backend may also report (D19). The hub's copy is dropped while the
     * direct backend answers.
     */
    val isHub: Boolean get() = false
    /** How long a read or command may take; null: [Home]'s 10 s. A service that signs in, connects and asks (Meross) needs more. */
    val timeoutMs: Long? get() = null
    /** How long a camera picture may take; null: [Home.SNAPSHOT_TIMEOUT_MS]. A battery camera has to wake first (Ring: 30 s). */
    val snapshotTimeoutMs: Long? get() = null
    suspend fun devices(): Outcome<List<HomeDevice>>
    suspend fun state(deviceId: String): Outcome<DeviceState>
    /** Sends [command]; the answer is what the device reports right after, which clouds often lag. */
    suspend fun command(deviceId: String, command: DeviceCommand): Outcome<DeviceState>
    /** A JPEG from a camera. */
    suspend fun snapshot(deviceId: String): Outcome<ByteArray>
    /** What a camera or doorbell reported since [sinceMs] (ms since 1970), newest first. Only devices with [Capability.Events]. */
    suspend fun events(deviceId: String, sinceMs: Long): Outcome<List<DeviceEvent>> =
        Outcome.Failure(AppError.ToolFailed(displayName, "it doesn't report events"))
    /** Whether this service has recorded clips to browse (Ring): the Home tab shows its Clips chip only then. */
    val hasClips: Boolean get() = false
    /** The clips recorded between [sinceMs] and [untilMs] (ms since 1970), newest first, from all this service's cameras. */
    suspend fun clips(sinceMs: Long, untilMs: Long): Outcome<ClipsPage> =
        Outcome.Failure(AppError.ToolFailed(displayName, "it has no clips"))
    /** A fresh address the player can stream clip [clipId] from. Services sign these, and they expire, so it is asked for at play time. */
    suspend fun clipUrl(clipId: String): Outcome<String> =
        Outcome.Failure(AppError.ToolFailed(displayName, "it has no clips"))
    /** Whether this service can show live video (C32). */
    val hasLive: Boolean get() = false
    /** Opens live video from a camera. The caller must [LiveFeed.stop] it; a service also ends it on its own after its time limit. */
    suspend fun startLive(deviceId: String): Outcome<LiveFeed> =
        Outcome.Failure(AppError.ToolFailed(displayName, "it has no live view"))
    /** [clipUrl] plus the headers the player must send; a service whose addresses are signed in themselves (Ring) needs no more. */
    suspend fun clipSource(clipId: String): Outcome<ClipSource> = when (val r = clipUrl(clipId)) {
        is Outcome.Success -> Outcome.Success(ClipSource(r.value))
        is Outcome.Failure -> r
    }
}

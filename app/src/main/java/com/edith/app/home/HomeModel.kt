package com.edith.app.home

import com.edith.app.core.error.Outcome
import com.edith.app.tools.framework.Integration

/**
 * The smart home, independent of any service (C20 §1, widened for Jarvis parity in C25/D19). Every backend (Alexa,
 * Ring, Resideo, …) maps its devices onto these types; the tools and the Home tab only see these.
 */
enum class DeviceType(val label: String) {
    Light("Light"), Switch("Switch"), Plug("Plug"), Fan("Fan"), Thermostat("Thermostat"),
    TemperatureSensor("Temperature sensor"), Lock("Lock"), GarageDoor("Garage door"), Camera("Camera"),
    Doorbell("Doorbell"), SecurityPanel("Security panel"), Sensor("Sensor"), Speaker("Speaker"), Other("Device"),
}

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

    companion object {
        val DEFAULT_COLORS = listOf("red", "orange", "yellow", "green", "blue", "purple", "pink", "white")
        /** Alexa's white tones (Jarvis's `set_white`). */
        val DEFAULT_WHITES = listOf("warm white", "soft white", "daylight white", "cool white")
    }
}

enum class LockState(val label: String) { Locked("Locked"), Unlocked("Unlocked"), Jammed("Jammed") }
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
)

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
}

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
    suspend fun devices(): Outcome<List<HomeDevice>>
    suspend fun state(deviceId: String): Outcome<DeviceState>
    /** Sends [command]; the answer is what the device reports right after, which clouds often lag. */
    suspend fun command(deviceId: String, command: DeviceCommand): Outcome<DeviceState>
    /** A JPEG from a camera. */
    suspend fun snapshot(deviceId: String): Outcome<ByteArray>
}

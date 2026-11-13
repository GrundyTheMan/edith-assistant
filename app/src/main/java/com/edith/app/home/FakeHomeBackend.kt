package com.edith.app.home

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.time.Clock
import com.edith.app.tools.framework.IntegrationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Demo home (C25 §9): a made-up house for debug builds and tests, so the tools and the Home tab can be tried
 * without any real service. Locks, the garage and the thermostat report a change [lagMs] late, as real clouds do.
 * Debug builds only: the app passes it to [Home] when `BuildConfig.DEBUG`, so R8 drops it from release.
 */
class FakeHomeBackend(
    private val clock: Clock,
    /** The doorbell's picture (a JPEG drawn by the app; any bytes in tests). */
    private val picture: () -> ByteArray,
    private val lagMs: Long = 4_000,
    override val id: String = ID,
    override val displayName: String = "Demo home",
    override val isHub: Boolean = false,
) : HomeBackend {
    override val status: StateFlow<IntegrationStatus> = MutableStateFlow(IntegrationStatus.Connected).asStateFlow()

    private val thermostat = Capability.Thermostat(listOf("heat", "cool", "auto", "off"), TempUnit.Fahrenheit)

    private val devices = mutableListOf(
        device("porch", "Porch light", "Outside", DeviceType.Light, setOf(Capability.OnOff, Capability.Brightness), DeviceState(on = false, brightness = 100)),
        device("lamp", "Lamp", "Living room", DeviceType.Light,
            setOf(Capability.OnOff, Capability.Brightness, Capability.Color(), Capability.WhiteTone()), DeviceState(on = true, brightness = 60, white = "soft white")),
        device("plug", "Coffee maker", "Kitchen", DeviceType.Plug, setOf(Capability.OnOff), DeviceState(on = false)),
        device("fan", "Ceiling fan", "Bedroom", DeviceType.Fan, setOf(Capability.OnOff, Capability.Percentage), DeviceState(on = false, percentage = 33)),
        device("thermostat", "Thermostat", "Hallway", DeviceType.Thermostat, setOf(thermostat),
            DeviceState(temperature = 68.0, setpoint = 69.0, mode = "heat")),
        device("front-lock", "Front door", "Entry", DeviceType.Lock, setOf(Capability.LockUnlock), DeviceState(lock = LockState.Locked, battery = 80)),
        device("back-lock", "Back door", "Kitchen", DeviceType.Lock, setOf(Capability.LockUnlock), DeviceState(lock = LockState.Locked, battery = 15)),
        device("garage", "Garage door", "Garage", DeviceType.GarageDoor, setOf(Capability.OpenClose), DeviceState(door = DoorState.Closed)),
        device("doorbell", "Doorbell", "Entry", DeviceType.Doorbell, setOf(Capability.Snapshot, Capability.Events), DeviceState(battery = 90)),
    )

    /** Changes the "cloud" hasn't reported yet: (device id, when it shows, the new state). */
    private val pending = mutableListOf<Triple<String, Long, DeviceState>>()

    /** For tests: make a backend that doesn't answer. */
    var failWith: AppError? = null

    /** For tests: every command received, in order. */
    val received = mutableListOf<Pair<String, DeviceCommand>>()

    override suspend fun devices(): Outcome<List<HomeDevice>> = synchronized(this) {
        failWith?.let { return Outcome.Failure(it) }
        settle()
        Outcome.Success(devices.toList())
    }

    override suspend fun state(deviceId: String): Outcome<DeviceState> = synchronized(this) {
        failWith?.let { return Outcome.Failure(it) }
        settle()
        val d = devices.firstOrNull { it.id == deviceId } ?: return Outcome.Failure(AppError.ToolFailed(displayName, "no such device"))
        Outcome.Success(d.state ?: DeviceState())
    }

    override suspend fun command(deviceId: String, command: DeviceCommand): Outcome<DeviceState> = synchronized(this) {
        failWith?.let { return Outcome.Failure(it) }
        settle()
        val i = devices.indexOfFirst { it.id == deviceId }
        if (i < 0) return Outcome.Failure(AppError.ToolFailed(displayName, "no such device"))
        val d = devices[i]
        if (!HomeActions.can(d, command)) return Outcome.Failure(AppError.ToolFailed(displayName, "${d.name} can't do that"))
        received += deviceId to command
        val s = d.state ?: DeviceState()
        val next = when (command) {
            DeviceCommand.TurnOn -> s.copy(on = true)
            DeviceCommand.TurnOff -> s.copy(on = false)
            is DeviceCommand.SetBrightness -> s.copy(on = command.percent > 0, brightness = command.percent)
            is DeviceCommand.SetPercentage -> s.copy(on = command.percent > 0, percentage = command.percent)
            is DeviceCommand.SetColor -> s.copy(on = true, color = command.name, white = null)
            is DeviceCommand.SetWhite -> s.copy(on = true, white = command.name, color = null)
            is DeviceCommand.SetTemperature -> s.copy(setpoint = command.value)
            is DeviceCommand.SetMode -> s.copy(mode = command.mode)
            DeviceCommand.Lock -> s.copy(lock = LockState.Locked)
            DeviceCommand.Unlock -> s.copy(lock = LockState.Unlocked)
            DeviceCommand.Open -> s.copy(door = DoorState.Open)
            DeviceCommand.Close -> s.copy(door = DoorState.Closed)
        }
        if (HomeActions.slow(d) && lagMs > 0) {
            pending.removeAll { it.first == deviceId }
            pending += Triple(deviceId, now() + lagMs, next)
            // The garage reports that it's moving straight away, as real openers do.
            val moving = when (command) {
                DeviceCommand.Open -> s.copy(door = DoorState.Opening)
                DeviceCommand.Close -> s.copy(door = DoorState.Closing)
                else -> s
            }
            devices[i] = d.copy(state = moving, stateAt = now())
            Outcome.Success(moving)
        } else {
            devices[i] = d.copy(state = next, stateAt = now())
            Outcome.Success(next)
        }
    }

    override suspend fun snapshot(deviceId: String): Outcome<ByteArray> {
        failWith?.let { return Outcome.Failure(it) }
        val d = synchronized(this) { devices.firstOrNull { it.id == deviceId } }
        if (d == null || !d.has(Capability.Snapshot)) return Outcome.Failure(AppError.ToolFailed(displayName, "that isn't a camera"))
        return Outcome.Success(picture())
    }

    private fun settle() {
        val now = now()
        val due = pending.filter { it.second <= now }
        for ((id, _, state) in due) {
            val i = devices.indexOfFirst { it.id == id }
            if (i >= 0) devices[i] = devices[i].copy(state = state, stateAt = now)
        }
        pending.removeAll(due)
    }

    private fun now() = clock.now().toEpochMilli()

    private fun device(id: String, name: String, room: String, type: DeviceType, caps: Set<Capability>, state: DeviceState) =
        HomeDevice(id, name, room, type, caps, this.id, online = true, state = state, stateAt = null)

    companion object {
        const val ID = "demo"
    }
}

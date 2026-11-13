package com.edith.app.home.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edith.app.core.error.Outcome
import com.edith.app.core.time.Clock
import com.edith.app.di.viewModelFactory
import com.edith.app.home.Approval
import com.edith.app.home.Capability
import com.edith.app.home.CommandSource
import com.edith.app.home.DeviceCommand
import com.edith.app.home.DeviceType
import com.edith.app.home.Home
import com.edith.app.home.HomeActions
import com.edith.app.home.HomeDevice
import com.edith.app.home.HomeView
import com.edith.app.tools.framework.IntegrationStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Home tab's chips (C25 §5, Jarvis's `CHIPS`). */
enum class HomeChip(val label: String, val types: Set<DeviceType>?) {
    Favorites("Favorites", null),
    Lights("Lights", setOf(DeviceType.Light)),
    Plugs("Plugs and switches", setOf(DeviceType.Plug, DeviceType.Switch)),
    Fans("Fans", setOf(DeviceType.Fan)),
    Climate("Climate", setOf(DeviceType.Thermostat, DeviceType.TemperatureSensor)),
    Locks("Locks and doors", setOf(DeviceType.Lock, DeviceType.GarageDoor)),
    Cameras("Cameras", setOf(DeviceType.Camera, DeviceType.Doorbell)),
    All("All", null);

    fun includes(d: HomeDevice, favorites: Set<String>): Boolean = when (this) {
        Favorites -> d.key in favorites
        All -> true
        else -> d.type in types.orEmpty()
    }
}

/** A command waiting for the owner's OK on the tab's dialog: the same rule as the tools' cards (C25 §5). */
data class PendingCommand(val device: HomeDevice, val command: DeviceCommand, val screenOnly: Boolean)

data class HomeUi(
    val connected: Boolean = false,
    val view: HomeView = HomeView(),
    val chip: HomeChip = HomeChip.All,
    val favorites: Set<String> = emptySet(),
    val now: Long = 0,
    /** Thermostat setpoints being stepped, not sent yet (sent 1.2 s after the last tap). */
    val stepping: Map<String, Double> = emptyMap(),
    val pending: PendingCommand? = null,
    /** A camera picture to show over the page: (device name, JPEG). */
    val picture: Pair<String, ByteArray>? = null,
    /** Devices whose picture is being fetched. */
    val fetching: Set<String> = emptySet(),
    val message: String? = null,
) {
    /** The devices the chip shows. Favorites with none starred show everything, with a hint. */
    val shown: List<HomeDevice> get() = view.devices.filter { chip.includes(it, favorites) }
}

/** The Home tab (C25 §5): Jarvis's Home page on the phone. */
class HomeViewModel(private val home: Home, private val clock: Clock) : ViewModel() {
    private val local = MutableStateFlow(Local())
    private data class Local(
        val stepping: Map<String, Double> = emptyMap(),
        val pending: PendingCommand? = null,
        val picture: Pair<String, ByteArray>? = null,
        val fetching: Set<String> = emptySet(),
        val message: String? = null,
        val now: Long = 0,
        val chip: HomeChip? = null,
    )

    val state: StateFlow<HomeUi> = combine(home.view, home.choices, home.status, local) { view, choices, status, l ->
        val saved = choices.chip?.let { name -> HomeChip.entries.firstOrNull { it.name == name } }
        HomeUi(
            connected = status == IntegrationStatus.Connected, view = view,
            chip = l.chip ?: saved ?: HomeChip.All,   // never switches by itself (starring one mustn't jump to Favorites)
            favorites = choices.favorites, now = l.now, stepping = l.stepping, pending = l.pending,
            picture = l.picture, fetching = l.fetching, message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    private val steps = HashMap<String, Job>()

    init {
        viewModelScope.launch {
            // Read again whenever a service comes or goes (Demo home switched on, a sign-in).
            home.backends.map { list -> list.map { it.id } }.distinctUntilChanged().collect { refresh() }
        }
        viewModelScope.launch {
            while (true) {   // the states' ages ("3 min ago") move on
                local.value = local.value.copy(now = clock.now().toEpochMilli())
                delay(30_000)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            home.refresh()
            tick()
        }
    }

    fun selectChip(chip: HomeChip) {
        local.value = local.value.copy(chip = chip)
        viewModelScope.launch { home.setChip(chip.name) }
    }

    /** A tile's one tap: on ↔ off. */
    fun toggle(device: HomeDevice) = send(device, if (device.state?.on == true) DeviceCommand.TurnOff else DeviceCommand.TurnOn)

    /** Sends [command], or asks first when the rule says so (C20 §2): the dialog then calls [confirm]. */
    fun send(device: HomeDevice, command: DeviceCommand) {
        when (HomeActions.approval(device, command)) {
            Approval.None -> run(device, command)
            Approval.Card -> local.value = local.value.copy(pending = PendingCommand(device, command, screenOnly = false))
            Approval.ScreenOnly -> local.value = local.value.copy(pending = PendingCommand(device, command, screenOnly = true))
        }
    }

    fun confirm() {
        val p = local.value.pending ?: return
        local.value = local.value.copy(pending = null)
        run(p.device, p.command)
    }

    fun dismiss() {
        local.value = local.value.copy(pending = null)
    }

    /** Thermostat − / +: the new setpoint is sent 1.2 s after the last tap (C25 §5). */
    fun step(device: HomeDevice, delta: Double) {
        val t = device.capability<Capability.Thermostat>() ?: return
        val from = local.value.stepping[device.key] ?: device.state?.setpoint ?: return
        val to = (from + delta).coerceIn(t.min, t.max)
        local.value = local.value.copy(stepping = local.value.stepping + (device.key to to))
        steps.remove(device.key)?.cancel()
        steps[device.key] = viewModelScope.launch {
            delay(STEP_DELAY_MS)
            local.value = local.value.copy(stepping = local.value.stepping - device.key)
            if (to != device.state?.setpoint) send(device, DeviceCommand.SetTemperature(to))
        }
    }

    fun setFavorite(device: HomeDevice, on: Boolean) {
        viewModelScope.launch { home.setFavorite(device, on) }
    }

    fun hide(device: HomeDevice) {
        viewModelScope.launch { home.setHidden(device.key, device.name, true) }
    }

    fun snapshot(device: HomeDevice) {
        local.value = local.value.copy(fetching = local.value.fetching + device.key)
        viewModelScope.launch {
            val r = home.snapshot(device)
            local.value = local.value.copy(fetching = local.value.fetching - device.key)
            when (r) {
                is Outcome.Success -> local.value = local.value.copy(picture = device.name to r.value)
                is Outcome.Failure -> local.value = local.value.copy(message = "No picture from ${device.name}: ${Home.why(r.error)}.")
            }
        }
    }

    fun closePicture() {
        local.value = local.value.copy(picture = null)
    }

    fun messageShown() {
        local.value = local.value.copy(message = null)
    }

    private fun run(device: HomeDevice, command: DeviceCommand) {
        viewModelScope.launch {
            when (val r = home.command(device, command, CommandSource.HomeTab)) {
                is Outcome.Success -> tick()
                is Outcome.Failure -> local.value = local.value.copy(message = "${device.name} didn't respond: ${Home.why(r.error)}.")
            }
        }
    }

    private fun tick() {
        local.value = local.value.copy(now = clock.now().toEpochMilli())
    }

    companion object {
        const val STEP_DELAY_MS = 1_200L
        val Factory = viewModelFactory { c, _ -> HomeViewModel(c.home, c.clock) }
    }
}

package com.edith.app.home.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edith.app.core.error.Outcome
import com.edith.app.core.time.Clock
import com.edith.app.di.viewModelFactory
import com.edith.app.home.Approval
import com.edith.app.home.Capability
import com.edith.app.home.CommandSource
import com.edith.app.home.DeviceClip
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
    Printer("Printer", setOf(DeviceType.Printer)),
    All("All", null);

    fun includes(d: HomeDevice, favorites: Set<String>): Boolean = when (this) {
        Favorites -> d.key in favorites
        All -> true
        else -> d.type in types.orEmpty()
    }
}

/** A command waiting for the owner's OK on the tab's dialog: the same rule as the tools' cards (C25 §5). */
data class PendingCommand(val device: HomeDevice, val command: DeviceCommand, val screenOnly: Boolean)

/** What the Home tab shows. */
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
    /** Whether a connected service has clips: the Clips chip shows only then. */
    val hasClips: Boolean = false,
    /** The clips list while it is open (null: closed). */
    val clips: ClipsUi? = null,
    /** The live view while it is open (null: closed). */
    val live: LiveUi? = null,
    /** A Jarvis is paired (C38): the printer tile then says Details, and the Printer page can reach the printer through it. */
    val jarvisPaired: Boolean = false,
) {
    /** The devices the chip shows. Favorites with none starred show everything, with a hint. */
    val shown: List<HomeDevice> get() = view.devices.filter { chip.includes(it, favorites) }
}

/** The Clips list (C27 §Clips): newest first, older windows loaded as the owner scrolls. */
data class ClipsUi(
    val clips: List<DeviceClip> = emptyList(),
    val loading: Boolean = true,
    /** The account has no plan for recordings (Ring Protect). */
    val noPlan: Boolean = false,
    val problems: List<String> = emptyList(),
    /** Older windows remain to load. */
    val more: Boolean = true,
    /** The clip being played and the address it streams from, once Ring gave one. */
    val playing: DeviceClip? = null,
    val playUrl: String? = null,
    /** Headers the player sends with [playUrl] (Blink's sign-in). */
    val playHeaders: Map<String, String> = emptyMap(),
    val playError: String? = null,
)

/** The Home tab (C25 §5): Jarvis's Home page on the phone. */
class HomeViewModel(private val home: Home, private val clock: Clock, private val jarvisPaired: () -> Boolean = { false }) : ViewModel() {
    private val local = MutableStateFlow(Local())
    private data class Local(
        val stepping: Map<String, Double> = emptyMap(),
        val pending: PendingCommand? = null,
        val picture: Pair<String, ByteArray>? = null,
        val fetching: Set<String> = emptySet(),
        val message: String? = null,
        val now: Long = 0,
        val chip: HomeChip? = null,
        val clips: ClipsUi? = null,
        val live: LiveUi? = null,
    )

    val state: StateFlow<HomeUi> = combine(home.view, home.choices, home.status, local, home.hasClips) { view, choices, status, l, hasClips ->
        val saved = choices.chip?.let { name -> HomeChip.entries.firstOrNull { it.name == name } }
        HomeUi(
            connected = status == IntegrationStatus.Connected, view = view,
            chip = l.chip ?: saved ?: HomeChip.All,   // never switches by itself (starring one mustn't jump to Favorites)
            favorites = choices.favorites, now = l.now, stepping = l.stepping, pending = l.pending,
            picture = l.picture, fetching = l.fetching, message = l.message,
            hasClips = hasClips, clips = l.clips, live = l.live, jarvisPaired = jarvisPaired(),
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
                local.value = local.value.copy(now = clock.now().toEpochMilliseconds())
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

    // ---- Live view (C32) ----

    private var liveFeed: com.edith.app.home.LiveFeed? = null
    private var liveJob: Job? = null

    /** Opens a live view of [device]; one at a time. The dialog shows a spinner until the service has the stream. */
    fun startLive(device: HomeDevice) {
        stopLive()
        local.value = local.value.copy(live = LiveUi(device.name))
        liveJob = viewModelScope.launch {
            when (val r = home.startLive(device)) {
                is Outcome.Failure -> local.value = local.value.copy(live = LiveUi(device.name, error = "No live view from ${device.name}: ${Home.why(r.error)}."))
                is Outcome.Success -> {
                    val feed = r.value
                    if (local.value.live?.name != device.name) { feed.stop(); return@launch }   // closed or replaced meanwhile
                    liveFeed = feed
                    local.value = local.value.copy(live = LiveUi(device.name, feed = feed))
                    val why = feed.ended.filterNotNull().first()
                    if (liveFeed === feed) {   // it ended by itself, not because the owner closed it
                        liveFeed = null
                        local.value = local.value.copy(live = LiveUi(device.name, endedBecause = why))
                    }
                }
            }
        }
    }

    /** Stops the live view and the camera's stream. Also called when the app leaves the screen (battery). */
    fun stopLive() {
        liveJob?.cancel(); liveJob = null
        liveFeed?.stop(); liveFeed = null
        if (local.value.live != null) local.value = local.value.copy(live = null)
    }

    override fun onCleared() {
        liveFeed?.stop(); liveFeed = null
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
        local.value = local.value.copy(now = clock.now().toEpochMilliseconds())
    }

    // ---- Clips (C27) ----

    private var clipsJob: Job? = null
    /** The oldest moment already loaded; the next window ends here. */
    private var clipsFrom = 0L

    fun openClips() {
        val now = clock.now().toEpochMilliseconds()
        clipsFrom = now
        local.value = local.value.copy(clips = ClipsUi())
        loadMoreClips()
    }

    /** Loads older windows until some clips turn up (a quiet week shows none) or the history ends. */
    fun loadMoreClips() {
        val ui = local.value.clips ?: return
        if (clipsJob?.isActive == true || !ui.more) return
        local.value = local.value.copy(clips = ui.copy(loading = true))
        val oldest = clock.now().toEpochMilliseconds() - CLIPS_MAX_DAYS * DAY_MS
        clipsJob = viewModelScope.launch {
            var found = emptyList<DeviceClip>(); var noPlan = false; val problems = mutableListOf<String>()
            var windows = 0
            while (found.isEmpty() && clipsFrom > oldest && windows < CLIPS_WINDOWS_PER_LOAD) {
                val until = clipsFrom
                val since = (until - CLIPS_WINDOW_DAYS * DAY_MS).coerceAtLeast(oldest)
                val r = home.clips(since, until)
                clipsFrom = since; windows++
                found = r.clips; noPlan = r.noPlan; problems += r.problems
                if (noPlan || r.problems.isNotEmpty()) break   // no plan: nothing older either; a failure: say so, don't loop
            }
            val current = local.value.clips ?: return@launch   // closed meanwhile
            local.value = local.value.copy(clips = current.copy(
                clips = (current.clips + found).distinctBy { it.id }.sortedByDescending { it.at },
                loading = false, noPlan = noPlan || current.noPlan, problems = problems.distinct(),
                more = !noPlan && problems.isEmpty() && clipsFrom > oldest,
            ))
        }
    }

    fun closeClips() {
        clipsJob?.cancel(); clipsJob = null
        local.value = local.value.copy(clips = null)
    }

    /** Asks the service for a fresh address (they're signed and expire) and plays from it. */
    fun play(clip: DeviceClip) {
        val ui = local.value.clips ?: return
        local.value = local.value.copy(clips = ui.copy(playing = clip, playUrl = null, playError = null))
        viewModelScope.launch {
            val r = home.clipSource(clip)
            val current = local.value.clips ?: return@launch
            if (current.playing != clip) return@launch   // another clip, or closed
            local.value = local.value.copy(clips = when (r) {
                is Outcome.Success -> current.copy(playUrl = r.value.url, playHeaders = r.value.headers)
                is Outcome.Failure -> current.copy(playError = "The clip can't be played: ${Home.why(r.error)}.")
            })
        }
    }

    fun closePlayer() {
        val ui = local.value.clips ?: return
        local.value = local.value.copy(clips = ui.copy(playing = null, playUrl = null, playHeaders = emptyMap(), playError = null))
    }

    companion object {
        const val STEP_DELAY_MS = 1_200L
        /** Clips load three days at a time, back as far as Ring keeps them with a plan (60 days). */
        const val CLIPS_WINDOW_DAYS = 3L
        const val CLIPS_MAX_DAYS = 60L
        const val CLIPS_WINDOWS_PER_LOAD = 4
        private const val DAY_MS = 86_400_000L
        val Factory = viewModelFactory { c, _ -> HomeViewModel(c.home, c.clock) { c.accounts.jarvisConnection() != null } }
    }
}

package com.edith.app.home

import com.edith.app.alerts.Activity
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.time.Clock
import com.edith.app.tools.framework.Integration
import com.edith.app.tools.framework.IntegrationStatus
import com.edith.app.tools.framework.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** A backend that didn't answer, and why in plain words. */
data class HomeProblem(val backend: String, val name: String, val why: String) {
    /** "Alexa didn't answer: it didn't answer within 10 seconds." */
    val line: String get() = "$name: $why${if (why.endsWith(".")) "" else "."}"
}

/** The home as last read (C25 §2). */
data class HomeView(
    /** Every visible device, merged across backends, hidden ones left out. */
    val devices: List<HomeDevice> = emptyList(),
    /** Hidden devices still reported, for Settings → Home. */
    val hidden: List<HomeDevice> = emptyList(),
    val problems: List<HomeProblem> = emptyList(),
    /** Backends that failed this read: their devices are their last-known copies (shown with their age). */
    val stale: Set<String> = emptySet(),
    val loading: Boolean = false,
    /** When the last read finished; null before the first. */
    val readAt: Long? = null,
)

/**
 * Every connected smart-home backend (C25 §2), and the integration the home tools belong to: they're offered while
 * at least one backend is connected. Reads all backends in parallel, each with its own limit, so one slow service
 * never empties the home; keeps each device's last-known state; sends commands, logs them, and checks the state again
 * a few seconds later, because the makers' clouds lag (C25 §6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Home(
    private val prefs: HomePrefs,
    private val log: CommandLog,
    private val scope: CoroutineScope,
    private val clock: Clock,
    /** Debug builds only: the fake backend behind the Demo home switch (C25 §9). Null in release. */
    private val demo: HomeBackend? = null,
    private val timeoutMs: Long = TIMEOUT_MS,
    /** When to check a device again after a command, from the command's time. */
    private val recheckAfterMs: (HomeDevice) -> List<Long> = ::recheckTimes,
    /** Where service calls run: off the main thread, which Android won't let read the network (tests with virtual time pass an empty context). */
    private val ioContext: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
) : Integration {
    override val id: String = ID

    private val registered = MutableStateFlow<List<HomeBackend>>(emptyList())
    private val registeredServices = MutableStateFlow<List<HomeService>>(emptyList())

    /** Every service the owner can sign in to, connected or not (Settings → Home). The Demo home isn't one. */
    val services: StateFlow<List<HomeService>> = registeredServices.asStateFlow()

    /** The owner's choices; [HomeChoices] defaults until the first read. */
    val choices: StateFlow<HomeChoices> = prefs.choices.stateIn(scope, SharingStarted.Eagerly, HomeChoices())

    /** C26–C31 register theirs at startup; the Demo home's fake is added while its switch is on. */
    val backends: StateFlow<List<HomeBackend>> = combine(registered, choices) { list, c ->
        if (demo != null && c.demo) list + demo else list
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Whether the Demo home switch exists (debug builds). */
    val hasDemo: Boolean get() = demo != null

    override val status: StateFlow<IntegrationStatus> = backends
        .flatMapLatest { list ->
            if (list.isEmpty()) flowOf(IntegrationStatus.NotConnected)
            else combine(list.map { it.status }) { all -> if (IntegrationStatus.Connected in all) IntegrationStatus.Connected else IntegrationStatus.NotConnected }
        }
        .stateIn(scope, SharingStarted.Eagerly, IntegrationStatus.NotConnected)

    private val raw = MutableStateFlow(Raw())
    private data class Raw(
        val devices: List<HomeDevice> = emptyList(),
        val problems: List<HomeProblem> = emptyList(),
        val stale: Set<String> = emptySet(),
        val loading: Boolean = false,
        val readAt: Long? = null,
    )

    /** What the Home tab shows. */
    val view: StateFlow<HomeView> = combine(raw, choices) { r, c -> present(r, c) }
        .stateIn(scope, SharingStarted.Eagerly, HomeView())

    /** Each device's last report, by [HomeDevice.key]: shown with its age when its backend doesn't answer. */
    private val known = ConcurrentHashMap<String, HomeDevice>()
    private val readLock = Mutex()
    private val rechecks = ConcurrentHashMap<String, Job>()

    /** A real service (C26–C31): its backend is read while it is signed in, and Settings lists it. */
    fun register(service: HomeService) {
        registeredServices.value = registeredServices.value.filterNot { it.backend.id == service.backend.id } + service
        register(service.backend)
    }

    fun register(backend: HomeBackend) {
        registered.value = registered.value.filterNot { it.id == backend.id } + backend
    }

    /**
     * A backend answers with an [Outcome] and never throws, but a bug or a surprising answer from a service (Amazon's, Ring's) must not take
     * the whole app down: it becomes that service's failure, said in words. Only the exception's type and place are logged, never its text
     * (it can hold tokens or cookies). Found on the owner's phone: a sign-in for Alexa crashed Edith.
     *
     * It also runs the call off the main thread. A service reads its HTTP answers in whatever thread called it; the Home tab and
     * Settings call from the main thread, where Android refuses network reads (`NetworkOnMainThreadException`). Small answers are
     * already in memory by then, which is why Ring and Meross got away with it; Amazon's registration answer is not.
     */
    private suspend fun <T> safely(b: HomeBackend, block: suspend () -> Outcome<T>): Outcome<T> = try {
        withContext(ioContext) { block() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LogTag.Home, "${b.id} failed unexpectedly: ${e.javaClass.name} at ${e.stackTrace.firstOrNull()}")
        Outcome.Failure(AppError.ToolFailed(b.displayName, "it hit an unexpected problem (${e.javaClass.simpleName})"))
    }

    private fun connected(): List<HomeBackend> = backends.value.filter { it.status.value == IntegrationStatus.Connected }

    /** Reads every connected backend now and returns the home as the owner sees it. */
    suspend fun refresh(): HomeView = readLock.withLock {
        raw.value = raw.value.copy(loading = true)
        val targets = connected()
        val answers = coroutineScope {
            targets.map { b ->
                async { b to (withTimeoutOrNull(b.timeoutMs ?: timeoutMs) { safely(b) { b.devices() } } ?: Outcome.Failure(AppError.Timeout)) }
            }.awaitAll()
        }
        val now = clock.now().toEpochMilli()
        val devices = mutableListOf<HomeDevice>()
        val problems = mutableListOf<HomeProblem>()
        val stale = mutableSetOf<String>()
        for ((b, outcome) in answers) when (outcome) {
            is Outcome.Success -> for (d in outcome.value) {
                val fresh = if (d.state != null && d.stateAt == null) d.copy(stateAt = now) else d
                // A device that reports no state keeps its last one (with its age).
                val kept = if (fresh.state == null) known[fresh.key]?.let { fresh.copy(state = it.state, stateAt = it.stateAt) } ?: fresh else fresh
                known[kept.key] = kept
                devices += kept
            }
            is Outcome.Failure -> {
                val why = why(outcome.error)
                Log.w(LogTag.Home, "Couldn't read ${b.id}: $why")
                problems += HomeProblem(b.id, b.displayName, why)
                stale += b.id
                devices += known.values.filter { it.backend == b.id }.sortedBy { it.name }
            }
        }
        // Devices of backends no longer connected are forgotten.
        val ids = backends.value.map { it.id }.toSet()
        known.keys.removeAll { key -> key.substringBefore(':') !in ids }
        raw.value = Raw(merge(devices, targets, stale), problems, stale, loading = false, readAt = now)
        present(raw.value, prefs.choices.first())
    }

    /** The visible devices, fresh, for the tools. */
    suspend fun devicesForTools(): HomeView = refresh()

    /**
     * Sends [command] to [device], logs it and schedules the re-checks (C25 §6, §8). The owner's OK, when it's needed,
     * was asked before this is called (the tool's card, the tab's dialog).
     */
    suspend fun command(device: HomeDevice, command: DeviceCommand, source: CommandSource): Outcome<DeviceState> {
        val backend = backends.value.firstOrNull { it.id == device.backend }
        val outcome = if (backend == null) Outcome.Failure(AppError.ToolFailed(device.backend, "that service isn't connected"))
        else withTimeoutOrNull(backend.timeoutMs ?: timeoutMs) { safely(backend) { backend.command(device.id, command) } } ?: Outcome.Failure(AppError.Timeout)
        val now = clock.now().toEpochMilli()
        val (action, value) = HomeActions.logParts(command)
        val result = when (outcome) {
            is Outcome.Success -> {
                update(device.copy(state = outcome.value, stateAt = now))
                val reported = HomeActions.stateText(device, outcome.value).ifEmpty { "Done" }
                // A lagging cloud first reports the old state: the log mustn't read as if the command did nothing.
                if (HomeActions.shows(outcome.value, command)) reported else "Sent; it still reported $reported at first"
            }
            is Outcome.Failure -> why(outcome.error)
        }
        try {
            log.add(LoggedCommand(now, device.name, device.backend, action, value, outcome is Outcome.Success, result, source))
        } catch (e: Exception) {
            Log.w(LogTag.Home, "Couldn't log a command", e)   // the command itself went through
        }
        Log.i(LogTag.Home, "Command $action to ${device.backend}: ${if (outcome is Outcome.Success) "ok" else "failed"}")
        if (outcome is Outcome.Success && backend != null && (!HomeActions.shows(outcome.value, command) || HomeActions.slow(device))) {
            recheck(backend, device, command, now)
        }
        return outcome
    }

    /** A camera's picture. */
    suspend fun snapshot(device: HomeDevice): Outcome<ByteArray> {
        val backend = backends.value.firstOrNull { it.id == device.backend }
            ?: return Outcome.Failure(AppError.ToolFailed(device.backend, "that service isn't connected"))
        return withTimeoutOrNull(backend.snapshotTimeoutMs ?: SNAPSHOT_TIMEOUT_MS) { safely(backend) { backend.snapshot(device.id) } } ?: Outcome.Failure(AppError.Timeout)
    }

    /** Opens live video from a camera (C32). The caller stops the feed; the service also ends it at its own limit. */
    suspend fun startLive(device: HomeDevice): Outcome<LiveFeed> {
        val backend = backends.value.firstOrNull { it.id == device.backend }
            ?: return Outcome.Failure(AppError.ToolFailed(device.backend, "that service isn't connected"))
        if (!backend.hasLive) return Outcome.Failure(AppError.ToolFailed(backend.displayName, "it has no live view"))
        return withTimeoutOrNull(LIVE_START_TIMEOUT_MS) { safely(backend) { backend.startLive(device.id) } } ?: Outcome.Failure(AppError.Timeout)
    }

    /** What a camera or doorbell reported since [sinceMs], newest first. */
    suspend fun events(device: HomeDevice, sinceMs: Long): Outcome<List<DeviceEvent>> {
        val backend = backends.value.firstOrNull { it.id == device.backend }
            ?: return Outcome.Failure(AppError.ToolFailed(device.backend, "that service isn't connected"))
        return withTimeoutOrNull(backend.timeoutMs ?: timeoutMs) { safely(backend) { backend.events(device.id, sinceMs) } } ?: Outcome.Failure(AppError.Timeout)
    }

    /** Whether any service that is signed in has clips to browse: the Clips chip shows only then. */
    val hasClips: StateFlow<Boolean> = backends
        .flatMapLatest { list ->
            val withClips = list.filter { it.hasClips }
            if (withClips.isEmpty()) flowOf(false) else combine(withClips.map { it.status }) { all -> IntegrationStatus.Connected in all }
        }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** What [clips] found: the clips (newest first), whether an account has no plan for them, and a line per service that failed. */
    data class ClipsResult(val clips: List<DeviceClip>, val noPlan: Boolean, val problems: List<String>)

    /** Clips from every connected service that has them, between [sinceMs] and [untilMs]. One service failing doesn't hide the others. */
    suspend fun clips(sinceMs: Long, untilMs: Long): ClipsResult {
        val clips = mutableListOf<DeviceClip>(); val problems = mutableListOf<String>(); var noPlan = false; var answered = 0
        for (b in connected().filter { it.hasClips }) {
            when (val r = withTimeoutOrNull(b.timeoutMs ?: timeoutMs) { safely(b) { b.clips(sinceMs, untilMs) } } ?: Outcome.Failure(AppError.Timeout)) {
                is Outcome.Success -> { answered++; clips += r.value.clips.map { it.copy(backend = b.id) }; noPlan = noPlan || r.value.noPlan }
                is Outcome.Failure -> problems += "${b.displayName} didn't answer: ${why(r.error)}."
            }
        }
        return ClipsResult(clips.sortedByDescending { it.at }, noPlan && clips.isEmpty(), problems)
    }

    /** A fresh address to play [clip] from. */
    suspend fun clipUrl(clip: DeviceClip): Outcome<String> {
        val backend = backends.value.firstOrNull { it.id == clip.backend }
            ?: return Outcome.Failure(AppError.ToolFailed(clip.backend, "that service isn't connected"))
        return withTimeoutOrNull(backend.timeoutMs ?: timeoutMs) { safely(backend) { backend.clipUrl(clip.id) } } ?: Outcome.Failure(AppError.Timeout)
    }

    /** Where to play [clip] from: a fresh address and any headers it needs. */
    suspend fun clipSource(clip: DeviceClip): Outcome<ClipSource> {
        val backend = backends.value.firstOrNull { it.id == clip.backend }
            ?: return Outcome.Failure(AppError.ToolFailed(clip.backend, "that service isn't connected"))
        return withTimeoutOrNull(backend.timeoutMs ?: timeoutMs) { safely(backend) { backend.clipSource(clip.id) } } ?: Outcome.Failure(AppError.Timeout)
    }

    /** The cameras and doorbells of each service the alerts have looked at, so the next look doesn't list the devices again (C33). */
    private val alertCameras = ConcurrentHashMap<String, List<HomeDevice>>()

    /**
     * What [backendId]'s cameras and doorbells reported since [sinceMs], for alerts (C33): the service's clips when it has
     * them (one call), else (Ring without a plan keeps events but no recordings) each camera's events.
     */
    suspend fun activity(backendId: String, sinceMs: Long): Outcome<List<Activity>> {
        val backend = connected().firstOrNull { it.id == backendId }
            ?: return Outcome.Failure(AppError.ToolFailed(backendId, "that service isn't connected"))
        val wait = backend.timeoutMs ?: timeoutMs
        if (backend.hasClips) {
            val page = withTimeoutOrNull(wait) { safely(backend) { backend.clips(sinceMs, clock.now().toEpochMilli()) } }
                ?: return Outcome.Failure(AppError.Timeout)
            when (page) {
                is Outcome.Failure -> return page
                is Outcome.Success -> if (!page.value.noPlan) {
                    return Outcome.Success(page.value.clips.map { Activity("$backendId:${it.id}", backendId, it.deviceName, it.at, it.kind) })
                }
            }
        }
        val cameras = cameras(backend, wait) ?: return Outcome.Failure(AppError.Timeout)
        val found = mutableListOf<Activity>()
        for (camera in cameras) {
            val events = withTimeoutOrNull(wait) { safely(backend) { backend.events(camera.id, sinceMs) } } ?: return Outcome.Failure(AppError.Timeout)
            when (events) {
                is Outcome.Failure -> return events
                is Outcome.Success -> events.value.forEach { found += Activity("$backendId:${camera.id}:${it.at}:${it.kind}", backendId, camera.name, it.at, it.kind) }
            }
        }
        return Outcome.Success(found)
    }

    private suspend fun cameras(backend: HomeBackend, wait: Long): List<HomeDevice>? {
        alertCameras[backend.id]?.let { return it }
        val r = withTimeoutOrNull(wait) { safely(backend) { backend.devices() } } ?: return null
        val list = (r as? Outcome.Success)?.value.orEmpty().filter { Capability.Events in it.capabilities }
        if (list.isNotEmpty()) alertCameras[backend.id] = list
        return list
    }

    /** The camera [name] of [backendId] that an alert is about, to take its picture; null if it isn't known (C33). */
    suspend fun alertCamera(backendId: String, name: String): HomeDevice? {
        val backend = connected().firstOrNull { it.id == backendId } ?: return null
        return cameras(backend, backend.timeoutMs ?: timeoutMs).orEmpty().firstOrNull { it.name == name && Capability.Snapshot in it.capabilities }
    }

    suspend fun setFavorite(device: HomeDevice, on: Boolean) = prefs.setFavorite(device.key, on)
    suspend fun setHidden(key: String, name: String, hidden: Boolean) = prefs.setHidden(key, name, hidden)
    suspend fun setDemo(on: Boolean) = prefs.setDemo(on)
    suspend fun setChip(chip: String) = prefs.setChip(chip)

    /** Asks the device again at [recheckAfterMs] until it shows the command done (or the last check). */
    private fun recheck(backend: HomeBackend, device: HomeDevice, command: DeviceCommand, sentAt: Long) {
        rechecks.remove(device.key)?.cancel()
        rechecks[device.key] = scope.launch {
            for (at in recheckAfterMs(device)) {
                delay((sentAt + at - clock.now().toEpochMilli()).coerceAtLeast(0))
                val state = withTimeoutOrNull(backend.timeoutMs ?: timeoutMs) { safely(backend) { backend.state(device.id) } }
                if (state is Outcome.Success) {
                    val current = known[device.key] ?: device
                    update(current.copy(state = state.value, stateAt = clock.now().toEpochMilli(), online = true))
                    if (HomeActions.shows(state.value, command)) break
                }
            }
        }
    }

    private fun update(device: HomeDevice) {
        known[device.key] = device
        raw.value = raw.value.let { r -> r.copy(devices = r.devices.map { if (it.key == device.key) device else it }) }
    }

    /**
     * D19: a device both a hub (Alexa) and a direct backend report (same name and type) is shown once: through the
     * direct backend while it answers, else through the hub (rather than the direct one's last-known copy).
     */
    private fun merge(devices: List<HomeDevice>, backends: List<HomeBackend>, stale: Set<String>): List<HomeDevice> {
        val hubs = backends.filter { it.isHub }.map { it.id }.toSet()
        if (hubs.isEmpty()) return devices
        fun same(d: HomeDevice) = DeviceNames.norm(d.name) to d.type
        val freshDirect = devices.filter { it.backend !in hubs && it.backend !in stale }.map(::same).toSet()
        val freshHub = devices.filter { it.backend in hubs && it.backend !in stale }.map(::same).toSet()
        return devices.filterNot { d ->
            if (d.backend in hubs) same(d) in freshDirect
            else d.backend in stale && same(d) in freshHub
        }
    }

    private fun present(r: Raw, c: HomeChoices): HomeView = HomeView(
        devices = r.devices.filterNot { it.key in c.hidden },
        hidden = r.devices.filter { it.key in c.hidden },
        problems = r.problems, stale = r.stale, loading = r.loading, readAt = r.readAt,
    )

    // ---- Integration ----

    override val notConnectedNote: String
        get() = "No smart-home service is connected. If asked about lights, locks, the thermostat or cameras, say the owner can connect one in Settings → Home."

    override fun connectedNote(tools: List<Tool>): String {
        val names = connected().joinToString { it.displayName }
        return "Smart home: connected to $names. Use home_devices to see devices and their state, home_control to act on one by its exact name. " +
            "When the owner asks for an action, do it: Edith shows a card herself when one is needed. Unlocking a door and opening the garage always need the owner to tap the card on the screen."
    }

    companion object {
        const val ID = "home"
        const val TIMEOUT_MS = 10_000L
        const val SNAPSHOT_TIMEOUT_MS = 15_000L
        /** A camera wakes, Blink starts the view and the first video arrives: a battery camera is slow (Jarvis waits 20 s for Blink alone). */
        const val LIVE_START_TIMEOUT_MS = 40_000L

        /** C25 §6: 3 s; thermostats, locks and garage doors also 10 s and 30 s. */
        fun recheckTimes(device: HomeDevice): List<Long> =
            if (HomeActions.slow(device)) listOf(3_000L, 10_000L, 30_000L) else listOf(3_000L)

        fun why(e: AppError): String = when (e) {
            AppError.Timeout -> "it didn't answer in time"
            is AppError.ToolFailed -> e.detail
            else -> e.userMessage
        }
    }
}

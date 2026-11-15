package com.edith.app.printer

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.secrets.SecretStore
import com.edith.app.core.time.Clock
import com.edith.app.home.Capability
import com.edith.app.home.DeviceCommand
import com.edith.app.home.DeviceState
import com.edith.app.home.DeviceType
import com.edith.app.home.HomeBackend
import com.edith.app.home.HomeDevice
import com.edith.app.home.HomeService
import com.edith.app.home.PrintState
import com.edith.app.home.ServiceState
import com.edith.app.home.SignInField
import com.edith.app.home.SignInForm
import com.edith.app.home.SignInResult
import com.edith.app.tools.framework.IntegrationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * A 3D printer running Klipper with Moonraker (C34, D19), on the home Wi-Fi: the owner types its address (and an API key if it
 * has one), and the printer shows on the Home tab as one device with its job, temperatures and camera. It can pause, resume
 * and cancel a print (cancel always asks first, C25's cards); it can't start prints or run macros (Jarvis has those on the PC).
 *
 * A printer is only reachable at home. Away from home it isn't an error: the card says it isn't reachable and the Home tab
 * shows no banner. The address and key are kept encrypted ([SecretStore] `home/printer`).
 */
class PrinterService(
    private val secrets: SecretStore,
    private val api: MoonrakerApi,
    private val clock: Clock,
) : HomeService {
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(ServiceState.NotConnected)
    override val state: StateFlow<ServiceState> = _state.asStateFlow()
    private val _connected = MutableStateFlow(IntegrationStatus.NotConnected)

    @Volatile private var conn: PrinterConn? = null
    /** The webcams, asked for once an answer came; null until then. */
    @Volatile private var cameras: List<PrinterCamera>? = null
    /** The slicer's estimate per file, so only the first read of a print asks for it. */
    private val estimates = ConcurrentHashMap<String, Double>()

    override val backend: HomeBackend = Backend()

    override val form = SignInForm(
        title = "3D printer",
        intro = "Edith talks to a Klipper printer's Moonraker over your home Wi-Fi, so the printer must be on and this phone on the same network. " +
            "Away from home the printer simply shows as not reachable.",
        fields = listOf(
            SignInField("address", "Printer address, e.g. 192.168.4.86"),
            SignInField("apikey", "API key (only if your printer needs one)", secret = true, optional = true),
        ),
    )

    override val removeHint: String? = null

    /** At startup: picks up a saved printer. */
    suspend fun load() {
        val saved = secrets.get(SECRET_KEY)?.let { text ->
            try { json.decodeFromString(Saved.serializer(), text) } catch (e: SerializationException) { Log.w(LogTag.Home, "The saved printer setup couldn't be read"); null }
        }
        val urls = saved?.let { PrinterAddress.parse(it.address).getOrNull() }
        conn = urls?.let { PrinterConn(it, saved.apiKey) }
        _state.value = if (conn == null) ServiceState.NotConnected else ServiceState.Connected
        _connected.value = if (conn == null) IntegrationStatus.NotConnected else IntegrationStatus.Connected
    }

    override suspend fun signIn(values: Map<String, String>): SignInResult {
        val typed = values["address"].orEmpty().trim()
        val urls = PrinterAddress.parse(typed).getOrElse { return SignInResult.Failed(it.message.orEmpty()) }
        val key = values["apikey"]?.trim()?.takeIf { it.isNotEmpty() }
        val attempt = PrinterConn(urls, key)
        return when (val r = api.get(attempt, "/printer/info")) {
            PrinterReply.Unreachable -> SignInResult.Failed("Couldn't reach a printer at $typed. Is it on, and is this phone on the same Wi-Fi?")
            is PrinterReply.Failed ->
                if (r.status == 401 || r.status == 403) SignInResult.Failed("The printer wants an API key. Type it in the second box.")
                else SignInResult.Failed("${r.why}. Is $typed a Klipper printer with Moonraker?")
            is PrinterReply.Ok -> {
                secrets.put(SECRET_KEY, json.encodeToString(Saved.serializer(), Saved(typed, key)))
                conn = attempt; cameras = null; estimates.clear()
                _state.value = ServiceState.Connected
                _connected.value = IntegrationStatus.Connected
                Log.i(LogTag.Home, "3D printer connected")
                SignInResult.Done
            }
        }
    }

    override suspend fun disconnect() {
        secrets.put(SECRET_KEY, null)
        conn = null; cameras = null; estimates.clear()
        _state.value = ServiceState.NotConnected
        _connected.value = IntegrationStatus.NotConnected
    }

    // ---- Reading ----

    private sealed interface Read {
        class Ok(val status: PrinterStatus) : Read
        /** Nothing answered: away from home, or the printer is off. */
        data object Away : Read
        class Failed(val why: String) : Read
    }

    private suspend fun read(c: PrinterConn): Read = when (val r = api.get(c, "/printer/objects/query?${MoonrakerParse.OBJECTS}")) {
        PrinterReply.Unreachable -> Read.Away
        is PrinterReply.Failed -> {
            if (r.status == 401 || r.status == 403) _state.value = ServiceState.NeedsSignIn
            Read.Failed(r.why)
        }
        is PrinterReply.Ok -> {
            _state.value = ServiceState.Connected
            val raw = MoonrakerParse.statusOf(r.value)
            val first = MoonrakerParse.status(raw)
            val active = first.state.print?.state.let { it == PrintState.Printing || it == PrintState.Paused }
            val estimate = if (active && first.filename != null) estimateFor(c, first.filename) else null
            Read.Ok(if (estimate != null) MoonrakerParse.status(raw, estimate) else first)
        }
    }

    private suspend fun estimateFor(c: PrinterConn, filename: String): Double? {
        estimates[filename]?.let { return it }
        val path = "/server/files/metadata?filename=${URLEncoder.encode(filename, "UTF-8").replace("+", "%20")}"
        val seconds = (api.get(c, path) as? PrinterReply.Ok)?.let { MoonrakerParse.estimatedTime(it.value) } ?: return null
        estimates[filename] = seconds
        return seconds
    }

    /** The webcams, asked for once the printer answers; a printer that lists none (or an old Moonraker) has no camera. */
    private suspend fun camerasFor(c: PrinterConn): List<PrinterCamera> {
        cameras?.let { return it }
        return when (val r = api.get(c, "/server/webcams/list")) {
            is PrinterReply.Ok -> MoonrakerParse.cameras(r.value, c.urls).also { cameras = it }
            is PrinterReply.Failed -> emptyList<PrinterCamera>().also { cameras = it }
            PrinterReply.Unreachable -> emptyList()
        }
    }

    private suspend fun device(c: PrinterConn, online: Boolean, state: DeviceState?): HomeDevice {
        val hasCamera = (if (online) camerasFor(c) else cameras.orEmpty()).isNotEmpty()
        return HomeDevice(
            id = DEVICE_ID, name = NAME, room = null, type = DeviceType.Printer,
            capabilities = setOfNotNull(Capability.PrintControl, Capability.Snapshot.takeIf { hasCamera }),
            backend = ID, online = online, state = state,
            // Home treats a null stateAt as "now"; an unreachable printer has no fresh state to date.
            stateAt = if (online && state != null) clock.now().toEpochMilli() else null,
        )
    }

    private inner class Backend : HomeBackend {
        override val id = ID
        override val displayName = NAME
        override val status: StateFlow<IntegrationStatus> = _connected.asStateFlow()
        /** A status read, the file's estimate and the webcam list, one after another. */
        override val timeoutMs: Long = 20_000
        override val snapshotTimeoutMs: Long = 12_000

        override suspend fun devices(): Outcome<List<HomeDevice>> {
            val c = conn ?: return notSignedIn()
            return when (val r = read(c)) {
                is Read.Ok -> Outcome.Success(listOf(device(c, true, r.status.state)))
                // Not at home is no error: the card says so, and the Home tab shows no banner.
                Read.Away -> Outcome.Success(listOf(device(c, false, null)))
                is Read.Failed -> failed(r.why)
            }
        }

        override suspend fun state(deviceId: String): Outcome<DeviceState> {
            val c = conn ?: return notSignedIn()
            return when (val r = read(c)) {
                is Read.Ok -> Outcome.Success(r.status.state)
                Read.Away -> failed(AWAY)
                is Read.Failed -> failed(r.why)
            }
        }

        override suspend fun command(deviceId: String, command: DeviceCommand): Outcome<DeviceState> {
            val c = conn ?: return notSignedIn()
            val path = when (command) {
                DeviceCommand.Pause -> "/printer/print/pause"
                DeviceCommand.Resume -> "/printer/print/resume"
                DeviceCommand.CancelPrint -> "/printer/print/cancel"
                else -> return failed("a printer can only pause, resume or cancel a print")
            }
            val now = when (val r = read(c)) {
                is Read.Ok -> r.status
                Read.Away -> return failed(AWAY)
                is Read.Failed -> return failed(r.why)
            }
            if (now.klippy != Klippy.Ready) return failed("Klipper isn't ready${now.state.print?.message?.let { ": $it" }.orEmpty()}")
            val job = now.state.print?.state ?: PrintState.Idle
            // Asking for what is already so is a success, not an error (a voice can say "pause" twice).
            when (command) {
                DeviceCommand.Pause -> { if (job == PrintState.Paused) return Outcome.Success(now.state); if (job != PrintState.Printing) return failed("it isn't printing") }
                DeviceCommand.Resume -> { if (job == PrintState.Printing) return Outcome.Success(now.state); if (job != PrintState.Paused) return failed("nothing is paused") }
                else -> if (job != PrintState.Printing && job != PrintState.Paused) return failed("there's no print to cancel")
            }
            when (val r = api.post(c, path)) {
                PrinterReply.Unreachable -> return failed(AWAY)
                is PrinterReply.Failed -> return failed(r.why)
                is PrinterReply.Ok -> Log.i(LogTag.Home, "Printer command sent (${command::class.simpleName})")
            }
            // What the printer reports right after; Home asks again if it still shows the old state (C25 section 6).
            return when (val after = read(c)) {
                is Read.Ok -> Outcome.Success(after.status.state)
                else -> Outcome.Success(now.state)
            }
        }

        override suspend fun snapshot(deviceId: String): Outcome<ByteArray> {
            val c = conn ?: return notSignedIn()
            val camera = camerasFor(c).firstOrNull() ?: return failed(if (cameras == null) AWAY else "it has no camera Edith can use")
            return when (val r = api.image(c, camera.snapshotUrl)) {
                is PrinterReply.Ok -> Outcome.Success(r.value)
                PrinterReply.Unreachable -> failed(AWAY)
                is PrinterReply.Failed -> failed(r.why)
            }
        }
    }

    private fun <T> failed(why: String): Outcome<T> = Outcome.Failure(AppError.ToolFailed(NAME, why))
    private fun <T> notSignedIn(): Outcome<T> = failed("it isn't connected — Settings → Home")

    @Serializable
    private data class Saved(val address: String, val apiKey: String? = null)

    companion object {
        const val ID = "printer"
        const val NAME = "3D printer"
        const val DEVICE_ID = "printer"
        const val SECRET_KEY = "home/printer"
        const val AWAY = "it isn't reachable: it works on your home Wi-Fi, or it is off"
    }
}

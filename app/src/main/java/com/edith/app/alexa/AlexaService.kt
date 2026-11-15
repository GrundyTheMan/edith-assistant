package com.edith.app.alexa

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.secrets.SecretStore
import com.edith.app.core.time.Clock
import com.edith.app.home.DeviceCommand
import com.edith.app.home.DeviceState
import com.edith.app.home.DeviceType
import com.edith.app.home.HomeBackend
import com.edith.app.home.HomeDevice
import com.edith.app.home.HomeService
import com.edith.app.home.PageSignIn
import com.edith.app.home.PageSignInStart
import com.edith.app.home.ServiceState
import com.edith.app.home.SignInForm
import com.edith.app.home.SignInResult
import com.edith.app.tools.framework.IntegrationStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * Alexa devices (C30, D10, D19): everything linked to the owner's Amazon account, through the unofficial connection the
 * Alexa app uses. **Part 1: sign-in, the device list and each device's state. Part 2: commands**, checked against Amazon's answer.
 *
 * Signing in happens on Amazon's own page ([PageSignIn]): the password goes to Amazon, never through Edith's code. What
 * Amazon hands back is a refresh token, kept encrypted ([SecretStore] `home/alexa`) with the Amazon site and nothing
 * else; Alexa's cookies and csrf token are got from it when needed and held only in memory. A refresh token Amazon no
 * longer accepts turns the service to [ServiceState.NeedsSignIn]: "Sign in again". [HomeBackend.isHub]: a device a
 * direct service also reports is shown through that one (D19).
 */
class AlexaService(
    private val secrets: SecretStore,
    private val api: AlexaApi,
    private val clock: Clock,
) : HomeService {
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(ServiceState.NotConnected)
    override val state: StateFlow<ServiceState> = _state.asStateFlow()
    private val _connected = MutableStateFlow(IntegrationStatus.NotConnected)

    private val sessionLock = Mutex()
    @Volatile private var saved: Saved? = null
    @Volatile private var session: AlexaSession? = null
    @Volatile private var pending: AlexaSignInStart? = null
    /** The device list (without states), kept briefly so reading one device's state doesn't list them all again. */
    @Volatile private var listed: Pair<Long, List<AlexaDevice>>? = null
    /** The last reading each device gave, shown with its age when it doesn't answer (Jarvis's `lastKnown`). */
    private val lastKnown = ConcurrentHashMap<String, Pair<AlexaReading, Long>>()

    override val backend: HomeBackend = Backend()
    override val form: SignInForm? = null

    override val pageSignIn: PageSignIn = object : PageSignIn {
        override val siteName = "Amazon"
        override fun begin(): PageSignInStart {
            val start = api.start(AmazonSite.DEFAULT)
            pending = start
            val origin = "https://www.${start.site.site}"
            return PageSignInStart(start.url, origin, mapOf("frc" to start.frc, "map-md" to start.mapMd), api::isFinish)
        }

        override suspend fun finish(address: String, cookies: String): SignInResult = finishSignIn(address, cookies)
    }

    override val removeHint = "Disconnecting forgets the sign-in on this phone. Edith may stay listed as a device in your Alexa app (More → Settings → Account Settings → Alexa Privacy) until you remove it there."

    /** At startup: picks up a saved sign-in. */
    suspend fun load() {
        val s = secrets.get(SECRET_KEY)?.let { text ->
            try { json.decodeFromString(Saved.serializer(), text) } catch (e: SerializationException) { Log.w(LogTag.Home, "The saved Alexa sign-in couldn't be read"); null }
        }
        saved = s
        _state.value = if (s == null) ServiceState.NotConnected else ServiceState.Connected
        _connected.value = if (s == null) IntegrationStatus.NotConnected else IntegrationStatus.Connected
    }

    override suspend fun signIn(values: Map<String, String>): SignInResult = SignInResult.Failed("Alexa signs in on Amazon's own page.")

    private suspend fun finishSignIn(address: String, cookies: String): SignInResult {
        val start = pending ?: return SignInResult.Failed("Start the sign-in again: that one has expired.")
        val code = api.authorizationCode(address)
            ?: return SignInResult.Failed("That address isn't where Amazon's sign-in ends. It should start with https://www.amazon.com/ap/maplanding and contain openid.oa2.authorization_code.")
        return when (val r = api.register(start, code, cookies)) {
            is AlexaReply.Ok -> {
                sessionLock.withLock {
                    val keep = Saved(r.value.refreshToken, start.site.site, r.value.deviceSerial, clock.now().toEpochMilli())
                    secrets.put(SECRET_KEY, json.encodeToString(Saved.serializer(), keep))
                    saved = keep; session = null; listed = null; lastKnown.clear()
                }
                pending = null
                _state.value = ServiceState.Connected
                _connected.value = IntegrationStatus.Connected
                Log.i(LogTag.Home, "Alexa signed in through ${start.site.site}")
                SignInResult.Done
            }
            AlexaReply.Rejected -> SignInResult.Failed("Amazon didn't accept the sign-in. Start again, and finish signing in to Amazon on its page.")
            is AlexaReply.Failed -> SignInResult.Failed("${r.why}.")
        }
    }

    override suspend fun disconnect() {
        sessionLock.withLock {
            secrets.put(SECRET_KEY, null)
            saved = null; session = null; pending = null; listed = null; lastKnown.clear()
        }
        _state.value = ServiceState.NotConnected
        _connected.value = IntegrationStatus.NotConnected
    }

    private fun needsSignIn() {
        if (_state.value == ServiceState.Connected) Log.w(LogTag.Home, "Amazon no longer accepts the saved Alexa sign-in")
        _state.value = ServiceState.NeedsSignIn
    }

    /** Alexa's cookies, got from the refresh token the first time and again when Amazon refuses them. Null: see [lastFailure]. */
    private suspend fun sessionFor(site: AmazonSite, refreshed: AlexaSession? = null): AlexaSession? = sessionLock.withLock {
        val s = saved ?: return null
        val have = session
        // [refreshed] is the session a call just got refused with: it isn't reused.
        if (have != null && have != refreshed) return have
        when (val r = api.session(site, s.refreshToken)) {
            is AlexaReply.Ok -> { session = r.value; r.value }
            AlexaReply.Rejected -> { needsSignIn(); null }
            is AlexaReply.Failed -> { lastFailure = r.why; null }
        }
    }

    @Volatile private var lastFailure: String? = null

    /** Runs [call] with working cookies; a refusal gets new cookies once and asks again. */
    private suspend fun <T> authed(call: suspend (AmazonSite, AlexaSession) -> AlexaReply<T>): Outcome<T> {
        val s = saved ?: return failed("it isn't signed in — Settings → Home")
        val site = AmazonSite.of(s.site) ?: AmazonSite.DEFAULT
        var stale: AlexaSession? = null
        repeat(2) {
            lastFailure = null
            val have = sessionFor(site, stale) ?: return if (_state.value == ServiceState.NeedsSignIn) failed(NEEDS_SIGN_IN) else failed(lastFailure ?: "Amazon can't be reached right now")
            when (val r = call(site, have)) {
                is AlexaReply.Ok -> { _state.value = ServiceState.Connected; return Outcome.Success(r.value) }
                AlexaReply.Rejected -> stale = have
                is AlexaReply.Failed -> return failed(r.why)
            }
        }
        return failed("Amazon didn't accept Edith's session just now; try again")
    }

    // ---- Devices ----

    private suspend fun deviceList(fresh: Boolean): Outcome<List<AlexaDevice>> {
        listed?.let { (at, list) -> if (!fresh && clock.now().toEpochMilli() - at < LIST_CACHE_MS) return Outcome.Success(list) }
        return when (val r = authed<JsonElement> { site, s -> api.endpoints(site, s) }) {
            is Outcome.Failure -> r
            is Outcome.Success -> {
                val list = AlexaParse.parseEndpoints(r.value)
                listed = clock.now().toEpochMilli() to list
                Outcome.Success(list)
            }
        }
    }

    /** The devices with their states now: batches of five by maker, all at once, each with its own deadline. */
    private suspend fun lookup(list: List<AlexaDevice>): List<HomeDevice> {
        val now = clock.now().toEpochMilli()
        val scale = (AmazonSite.of(saved?.site.orEmpty()) ?: AmazonSite.DEFAULT).scale
        val asked = list.filter(AlexaParse::shouldAskState)
        val readings = ConcurrentHashMap<String, AlexaReading>()
        val problem = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val failed = java.util.concurrent.atomic.AtomicInteger()
        val batches = AlexaParse.stateBatches(asked, STATE_BATCH)
        coroutineScope {
            batches.map { group ->
                async {
                    val answer = withTimeoutOrNull(STATE_DEADLINE_MS) {
                        authed<JsonElement> { site, s -> api.states(site, s, AlexaParse.stateRequests(group), STATE_DEADLINE_MS / 1000) }
                    }
                    when (answer) {
                        null -> { failed.incrementAndGet(); problem.set("no answer within ${STATE_DEADLINE_MS / 1000} s"); group.forEach { readings[it.entityId] = AlexaReading(null, noAnswer = true) } }
                        is Outcome.Failure -> { failed.incrementAndGet(); problem.set(whyOf(answer)); group.forEach { readings[it.entityId] = AlexaReading(null, noAnswer = true) } }
                        is Outcome.Success -> {
                            AlexaParse.stateRefusal(answer.value)?.let { failed.incrementAndGet(); problem.set(it) }
                            val parsed = AlexaParse.parseStates(answer.value, scale)
                            group.forEach { readings[it.entityId] = AlexaParse.readingOf(it, parsed) }
                        }
                    }
                }
            }.awaitAll()
        }
        if (batches.isNotEmpty() && failed.get() == batches.size) Log.w(LogTag.Home, "Alexa gave no device states: ${problem.get()}")
        return list.map { d -> device(d, readings[d.entityId], now, scale) }
    }

    private fun device(d: AlexaDevice, reading: AlexaReading?, now: Long, scale: TempScale): HomeDevice {
        var shown = reading
        var at: Long? = if (reading != null && !reading.noAnswer) now else null
        if (reading != null && !reading.noAnswer && (reading.state != null || reading.unreachable)) lastKnown[d.entityId] = reading to now
        // No answer this time: the last reading, with when it was reported.
        if (reading?.noAnswer == true) lastKnown[d.entityId]?.let { (last, lastAt) -> shown = last; at = lastAt }
        return HomeDevice(
            id = d.entityId, name = d.name, room = null, type = typeOf(d.type),
            capabilities = AlexaParse.capabilitiesOf(d, scale),
            backend = ID, online = shown?.unreachable != true, state = shown?.state, stateAt = at,
        )
    }

    private inner class Backend : HomeBackend {
        override val id = ID
        override val displayName = "Alexa"
        override val status: StateFlow<IntegrationStatus> = _connected.asStateFlow()
        override val isHub = true
        /** Token exchange, the device list, then the state batches (each up to 20 s). */
        override val timeoutMs: Long = 60_000

        override suspend fun devices(): Outcome<List<HomeDevice>> = when (val l = deviceList(fresh = true)) {
            is Outcome.Failure -> l
            is Outcome.Success -> Outcome.Success(lookup(l.value))
        }

        override suspend fun state(deviceId: String): Outcome<DeviceState> {
            val list = when (val l = deviceList(fresh = false)) { is Outcome.Failure -> return l; is Outcome.Success -> l.value }
            val d = list.firstOrNull { it.entityId == deviceId } ?: return failed("that device is no longer on the Alexa account")
            val h = lookup(listOf(d)).first()
            return h.state?.let { Outcome.Success(it) } ?: failed(if (h.online) "it reported no state" else "it isn't responding")
        }

        override suspend fun command(deviceId: String, command: DeviceCommand): Outcome<DeviceState> {
            val list = when (val l = deviceList(fresh = false)) { is Outcome.Failure -> return l; is Outcome.Success -> l.value }
            val d = list.firstOrNull { it.entityId == deviceId } ?: return failed("that device is no longer on the Alexa account")
            val scale = (AmazonSite.of(saved?.site.orEmpty()) ?: AmazonSite.DEFAULT).scale
            val parameters = AlexaParse.controlParameters(command, d, scale) ?: return failed("${d.name} can't do that through Alexa")
            val answer = when (val r = authed<JsonElement> { site, s -> api.control(site, s, d.entityId, parameters, CONTROL_DEADLINE_MS / 1000) }) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            // Amazon answers 200 to a refusal: only its answer says whether the device did it.
            AlexaParse.controlProblem(answer)?.let { return failed("${d.name}: $it") }
            Log.i(LogTag.Home, "Alexa command sent (${d.type})")
            // What the device reports right after; clouds often lag, and Home asks again (C25 §6).
            val now = lookup(listOf(d)).first()
            return Outcome.Success(now.state ?: lastKnown[d.entityId]?.first?.state ?: DeviceState())
        }

        override suspend fun snapshot(deviceId: String): Outcome<ByteArray> = failed("Alexa can't take camera pictures for Edith")
    }

    private fun <T> failed(why: String): Outcome<T> = Outcome.Failure(AppError.ToolFailed("Alexa", why))
    private fun whyOf(f: Outcome.Failure): String = (f.error as? AppError.ToolFailed)?.detail ?: f.error.userMessage

    @Serializable
    private data class Saved(val refreshToken: String, val site: String, val deviceSerial: String, val signedInAt: Long)

    companion object {
        const val ID = "alexa"
        const val SECRET_KEY = "home/alexa"
        const val NEEDS_SIGN_IN = "it needs signing in again — Settings → Home"
        /** Devices per state request: of the sizes Jarvis tried on a 78-device home, 5 got the most answers. */
        const val STATE_BATCH = 5
        /** How long a batch may take before the lookup goes on without it (Jarvis: 10 s cut off answers 20 s got). */
        const val STATE_DEADLINE_MS = 20_000L
        const val LIST_CACHE_MS = 5 * 60_000L
        /** How long a command may take to be answered. */
        const val CONTROL_DEADLINE_MS = 20_000L

        /** Alexa's category code as Edith's device type. */
        fun typeOf(code: String): DeviceType = when (code) {
            "LIGHT" -> DeviceType.Light
            "SMARTPLUG" -> DeviceType.Plug
            "SWITCH" -> DeviceType.Switch
            "FAN" -> DeviceType.Fan
            "THERMOSTAT" -> DeviceType.Thermostat
            "TEMPERATURE_SENSOR" -> DeviceType.TemperatureSensor
            "SMARTLOCK" -> DeviceType.Lock
            "GARAGE_DOOR" -> DeviceType.GarageDoor
            "CAMERA" -> DeviceType.Camera
            "DOORBELL" -> DeviceType.Doorbell
            "SECURITY_PANEL" -> DeviceType.SecurityPanel
            "CONTACT_SENSOR", "MOTION_SENSOR" -> DeviceType.Sensor
            "SPEAKER" -> DeviceType.Speaker
            else -> DeviceType.Other
        }
    }
}

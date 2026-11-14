package com.edith.app.meross

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
import com.edith.app.home.DoorState
import com.edith.app.home.HomeBackend
import com.edith.app.home.HomeDevice
import com.edith.app.home.HomeService
import com.edith.app.home.ServiceState
import com.edith.app.home.SignInField
import com.edith.app.home.SignInForm
import com.edith.app.home.SignInResult
import com.edith.app.tools.framework.IntegrationStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Meross garage door openers (C26, D10): the owner's Meross account, signed in with an email and password that are
 * typed once, used once and never kept. Only the token and key Meross hands back are saved, encrypted
 * ([SecretStore] `home/meross`). Devices come from Meross's HTTP list, their state and commands go over its MQTT
 * ([MerossBus]). A token Meross no longer accepts turns the service to [ServiceState.NeedsSignIn]: "Sign in again".
 */
class MerossService(
    private val secrets: SecretStore,
    private val api: MerossApi,
    private val bus: MerossBus,
    private val clock: Clock,
) : HomeService {
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(ServiceState.NotConnected)
    override val state: StateFlow<ServiceState> = _state.asStateFlow()
    private val _connected = MutableStateFlow(IntegrationStatus.NotConnected)

    @Volatile private var creds: MerossCreds? = null

    override val backend: HomeBackend = Backend()

    override val form = SignInForm(
        title = "Meross",
        intro = "Sign in with the email and password of your Meross account. Edith uses them once and doesn't keep them: " +
            "only the sign-in Meross gives back is saved, encrypted on this phone.",
        fields = listOf(
            SignInField("email", "Meross email", email = true),
            SignInField("password", "Meross password", secret = true),
            SignInField("code", "Code from Meross", onlyWhenAsked = true),
        ),
    )

    override val removeHint = "Disconnecting signs Edith out at Meross. You can also see signed-in apps in the Meross app."

    /** At startup: picks up a saved sign-in. */
    suspend fun load() {
        val saved = secrets.get(SECRET_KEY)?.let { text ->
            try { json.decodeFromString(Saved.serializer(), text).toCreds() } catch (e: SerializationException) { Log.w(LogTag.Home, "The saved Meross sign-in couldn't be read"); null }
        }
        creds = saved
        _state.value = if (saved == null) ServiceState.NotConnected else ServiceState.Connected
        _connected.value = if (saved == null) IntegrationStatus.NotConnected else IntegrationStatus.Connected
    }

    override suspend fun signIn(values: Map<String, String>): SignInResult {
        val email = values["email"]?.trim().orEmpty()
        val password = values["password"].orEmpty()
        val code = values["code"]?.trim()?.takeIf { it.isNotEmpty() }
        if (email.isEmpty() || password.isEmpty()) return SignInResult.Failed("Type your Meross email and password.")
        val install = creds?.installId ?: UUID.randomUUID().toString().uppercase()
        return when (val r = api.login(email, password, code, install)) {
            is MerossLogin.Ok -> {
                val old = creds
                secrets.put(SECRET_KEY, json.encodeToString(Saved.serializer(), Saved.of(r.creds)))
                creds = r.creds
                // Meross limits how many tokens an account may hold: the one this replaces goes back (a dead one does no harm).
                if (old != null && old.token != r.creds.token) api.logout(old)
                _state.value = ServiceState.Connected
                _connected.value = IntegrationStatus.Connected
                Log.i(LogTag.Home, "Meross signed in")
                SignInResult.Done
            }
            MerossLogin.NeedsCode -> SignInResult.NeedsCode("Meross sent a code (to your email or authenticator). Type it and sign in again.")
            MerossLogin.WrongCode -> SignInResult.NeedsCode("That code isn't right. Check it and try again.")
            MerossLogin.BadLogin -> SignInResult.Failed("Meross says the email or password isn't right.")
            MerossLogin.TooManyTokens -> SignInResult.Failed("Meross has too many sign-ins open for this account. Wait a while, or sign out of other apps, then try again.")
            is MerossLogin.Failed -> SignInResult.Failed("${r.why}.")
        }
    }

    override suspend fun disconnect() {
        creds?.let { api.logout(it) }
        secrets.put(SECRET_KEY, null)
        creds = null
        _state.value = ServiceState.NotConnected
        _connected.value = IntegrationStatus.NotConnected
    }

    private fun needsSignIn() {
        if (_state.value == ServiceState.Connected) Log.w(LogTag.Home, "Meross no longer accepts the saved sign-in")
        _state.value = ServiceState.NeedsSignIn
    }

    private inner class Backend : HomeBackend {
        override val id = ID
        override val displayName = "Meross"
        override val status: StateFlow<IntegrationStatus> = _connected.asStateFlow()
        /** HTTP, then a TLS connection, then the question: more than [com.edith.app.home.Home]'s usual 10 s. */
        override val timeoutMs: Long = 14_000

        override suspend fun devices(): Outcome<List<HomeDevice>> {
            val c = creds ?: return notSignedIn()
            val list = when (val r = api.devices(c)) {
                is MerossReply.Ok -> r.value
                MerossReply.Rejected -> return rejected()
                is MerossReply.Failed -> return failed(r.why)
            }
            val openers = list.filter { it.isGarageOpener }
            val online = openers.filter { it.online }
            val digests: Map<String, JsonObject?> = if (online.isEmpty()) emptyMap() else when (val r = bus.withSession(c) { s ->
                coroutineScope { online.map { d -> async { d.uuid to askDoors(s, d.uuid) } }.awaitAll() }.toMap()
            }) {
                is MerossReply.Ok -> r.value
                MerossReply.Rejected -> return rejected()
                is MerossReply.Failed -> return failed(r.why)
            }
            _state.value = ServiceState.Connected
            val now = clock.now().toEpochMilli()
            return Outcome.Success(openers.flatMap { d -> doors(d, digests[d.uuid], now) })
        }

        override suspend fun state(deviceId: String): Outcome<DeviceState> {
            val c = creds ?: return notSignedIn()
            val (uuid, channel) = split(deviceId)
            val answer = when (val r = bus.withSession(c) { s -> s.request(uuid, "GET", MerossProtocol.SYSTEM_ALL, JsonObject(emptyMap())) }) {
                is MerossReply.Ok -> r.value
                MerossReply.Rejected -> return rejected()
                is MerossReply.Failed -> return failed(r.why)
            }
            return when (answer) {
                is MerossReply.Ok -> doorState(answer.value, channel)?.let { Outcome.Success(it) } ?: failed("it didn't report the door")
                MerossReply.Rejected -> rejected()
                is MerossReply.Failed -> failed(answer.why)
            }
        }

        override suspend fun command(deviceId: String, command: DeviceCommand): Outcome<DeviceState> {
            val open = when (command) {
                DeviceCommand.Open -> true
                DeviceCommand.Close -> false
                else -> return failed("a garage door opener can only open and close")
            }
            val c = creds ?: return notSignedIn()
            val (uuid, channel) = split(deviceId)
            val payload = buildJsonObject {
                put("state", buildJsonObject { put("channel", channel); put("open", if (open) 1 else 0); put("uuid", uuid) })
            }
            val answer = when (val r = bus.withSession(c) { s -> s.request(uuid, "SET", MerossProtocol.GARAGE_STATE, payload) }) {
                is MerossReply.Ok -> r.value
                MerossReply.Rejected -> return rejected()
                is MerossReply.Failed -> return failed(r.why)
            }
            return when (answer) {
                // The opener accepted it; the door takes a while. The Home tab asks again as it moves (C25 §6).
                is MerossReply.Ok -> Outcome.Success(DeviceState(door = if (open) DoorState.Opening else DoorState.Closing, changedAt = clock.now().toEpochMilli()))
                MerossReply.Rejected -> rejected()
                is MerossReply.Failed -> failed(answer.why)
            }
        }

        override suspend fun snapshot(deviceId: String): Outcome<ByteArray> = failed("a garage door opener has no camera")
    }

    /** The opener's doors. A first answer that never comes (a cold connection) is asked for once more; the reason is logged. */
    private suspend fun askDoors(s: MerossSession, uuid: String): JsonObject? {
        repeat(2) { attempt ->
            when (val r = s.request(uuid, "GET", MerossProtocol.SYSTEM_ALL, JsonObject(emptyMap()))) {
                is MerossReply.Ok -> return r.value
                is MerossReply.Failed -> Log.w(LogTag.Home, "Meross: a garage opener didn't answer (try ${attempt + 1}): ${r.why}")
                MerossReply.Rejected -> return null
            }
        }
        return null
    }

    /** One [HomeDevice] per door the opener reports (one for an MSG100; an MSG200 has several). */
    private fun doors(d: MerossDevice, all: JsonObject?, now: Long): List<HomeDevice> {
        val reported = digest(all)
        if (!d.online || reported == null) {
            // Offline, or it didn't answer: one entry, not responding.
            return listOf(door(d, 0, d.name, null, online = false, now))
        }
        return reported.mapNotNull { it.obj() }.mapIndexed { i, entry ->
            val channel = entry.int("channel") ?: i
            door(d, channel, if (reported.size > 1) "${d.name} ${i + 1}" else d.name, doorFrom(entry), online = true, now)
        }
    }

    private fun door(d: MerossDevice, channel: Int, name: String, state: DeviceState?, online: Boolean, now: Long) =
        HomeDevice("${d.uuid}#$channel", name, null, DeviceType.GarageDoor, setOf(Capability.OpenClose), ID, online, state, if (state != null) now else null)

    private fun split(deviceId: String): Pair<String, Int> = deviceId.substringBefore('#') to (deviceId.substringAfter('#', "0").toIntOrNull() ?: 0)

    private fun digest(all: JsonObject?): JsonArray? = all?.get("all").obj()?.get("digest").obj()?.get("garageDoor") as? JsonArray

    private fun doorFrom(entry: JsonObject) = DeviceState(
        door = if (entry.int("open") == 1) DoorState.Open else DoorState.Closed,
        // `lmTime`: when the door last moved, in seconds.
        changedAt = entry.long("lmTime")?.takeIf { it > 0 }?.let { it * 1000 },
    )

    private fun doorState(all: JsonObject, channel: Int): DeviceState? {
        val entries = digest(all)?.mapNotNull { it.obj() } ?: return null
        return (entries.firstOrNull { it.int("channel") == channel } ?: entries.firstOrNull())?.let(::doorFrom)
    }

    private fun <T> notSignedIn(): Outcome<T> = Outcome.Failure(AppError.ToolFailed("Meross", "it isn't signed in — Settings → Home"))

    private fun <T> rejected(): Outcome<T> {
        needsSignIn()
        return Outcome.Failure(AppError.ToolFailed("Meross", NEEDS_SIGN_IN))
    }

    private fun <T> failed(why: String): Outcome<T> = Outcome.Failure(AppError.ToolFailed("Meross", why))

    @Serializable
    private data class Saved(val token: String, val key: String, val userId: String, val email: String, val domain: String, val mqttDomain: String, val installId: String) {
        fun toCreds() = MerossCreds(token, key, userId, email, domain, mqttDomain, installId)

        companion object {
            fun of(c: MerossCreds) = Saved(c.token, c.key, c.userId, c.email, c.domain, c.mqttDomain, c.installId)
        }
    }

    companion object {
        const val ID = "meross"
        const val SECRET_KEY = "home/meross"
        const val NEEDS_SIGN_IN = "it needs signing in again — Settings → Home"
    }
}

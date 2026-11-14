package com.edith.app.ring

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.secrets.SecretStore
import com.edith.app.core.time.Clock
import com.edith.app.home.Capability
import com.edith.app.home.ClipsPage
import com.edith.app.home.DeviceClip
import com.edith.app.home.DeviceCommand
import com.edith.app.home.DeviceEvent
import com.edith.app.home.DeviceState
import com.edith.app.home.DeviceType
import com.edith.app.home.HomeBackend
import com.edith.app.home.HomeDevice
import com.edith.app.home.HomeService
import com.edith.app.home.ServiceState
import com.edith.app.home.SignInField
import com.edith.app.home.SignInForm
import com.edith.app.home.SignInResult
import com.edith.app.tools.framework.IntegrationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Ring doorbells and cameras (C27, D10): the owner's Ring account, signed in with an email, a password and a 2FA code
 * that are typed once, used once and never kept. Only Ring's refresh token and this phone's hardware id are saved,
 * encrypted ([SecretStore] `home/ring`).
 *
 * **The refresh token rotates** (C23 trap 1): every refresh returns a new one and the old one stops working soon
 * after. So each new one is saved *before* the access token it came with is used, and refreshes never overlap
 * ([refreshLock]: two at once would both spend the same old token). A token Ring no longer accepts turns the service
 * to [ServiceState.NeedsSignIn]: "Sign in again". No live view (C32) and no alerts (C33) here.
 */
class RingService(
    private val secrets: SecretStore,
    private val api: RingApi,
    private val clock: Clock,
) : HomeService {
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(ServiceState.NotConnected)
    override val state: StateFlow<ServiceState> = _state.asStateFlow()
    private val _connected = MutableStateFlow(IntegrationStatus.NotConnected)

    private val refreshLock = Mutex()
    @Volatile private var saved: Saved? = null
    @Volatile private var access: Access? = null
    @Volatile private var sessionReady = false
    /** The phone id of a sign-in still waiting for its 2FA code: Ring ties the code to it, so each try must send the same one. */
    @Volatile private var pendingHardwareId: String? = null
    /** The cameras last listed, for events (they need the location id) and picture checks. */
    @Volatile private var cameras: List<RingCamera> = emptyList()

    override val backend: HomeBackend = Backend()

    override val form = SignInForm(
        title = "Ring",
        intro = "Sign in with the email and password of your Ring account. Ring then sends a code by text or email: type it " +
            "and sign in again. Edith uses what you type once and doesn't keep it: only the sign-in Ring gives back is " +
            "saved, encrypted on this phone. Ring has no official way for apps like Edith, so this can stop working if Ring changes.",
        fields = listOf(
            SignInField("email", "Ring email", email = true),
            SignInField("password", "Ring password", secret = true),
            SignInField("code", "Code from Ring", onlyWhenAsked = true),
        ),
    )

    override val removeHint = "Disconnecting forgets the sign-in on this phone. Edith may stay in the Ring app's list of authorised devices (Control Center) until you remove it there."

    /** At startup: picks up a saved sign-in. */
    suspend fun load() {
        val s = secrets.get(SECRET_KEY)?.let { text ->
            try { json.decodeFromString(Saved.serializer(), text) } catch (e: SerializationException) { Log.w(LogTag.Home, "The saved Ring sign-in couldn't be read"); null }
        }
        saved = s
        _state.value = if (s == null) ServiceState.NotConnected else ServiceState.Connected
        _connected.value = if (s == null) IntegrationStatus.NotConnected else IntegrationStatus.Connected
    }

    override suspend fun signIn(values: Map<String, String>): SignInResult {
        val email = values["email"]?.trim().orEmpty()
        val password = values["password"].orEmpty()
        val code = values["code"]?.trim()?.takeIf { it.isNotEmpty() }
        if (email.isEmpty() || password.isEmpty()) return SignInResult.Failed("Type your Ring email and password.")
        // The same hardware id each time, so Ring sees one phone, not a new one at every sign-in.
        val hardwareId = saved?.hardwareId ?: pendingHardwareId ?: UUID.randomUUID().toString().also { pendingHardwareId = it }
        return when (val r = api.signIn(email, password, code, hardwareId)) {
            is RingLogin.Ok -> {
                val token = r.tokens.refreshToken ?: return SignInResult.Failed("Ring signed in but gave no sign-in token to keep.")
                refreshLock.withLock {
                    keep(Saved(token, hardwareId))
                    pendingHardwareId = null
                    access = Access(r.tokens.accessToken, expiry(r.tokens))
                    sessionReady = false
                }
                _state.value = ServiceState.Connected
                _connected.value = IntegrationStatus.Connected
                Log.i(LogTag.Home, "Ring signed in")
                SignInResult.Done
            }
            is RingLogin.NeedsCode -> SignInResult.NeedsCode("${r.prompt} Type it and sign in again.")
            RingLogin.WrongCode -> SignInResult.NeedsCode("That code isn't right. Check it, or wait for a new one, and try again.")
            RingLogin.BadLogin -> SignInResult.Failed("Ring says the email or password isn't right.")
            RingLogin.TooManyCodes -> SignInResult.Failed("You've asked Ring for too many codes. Ring allows 10 in 10 minutes: wait a bit and try again.")
            is RingLogin.Failed -> SignInResult.Failed("${r.why}.")
        }
    }

    override suspend fun disconnect() {
        refreshLock.withLock {
            secrets.put(SECRET_KEY, null)
            saved = null; access = null; sessionReady = false; cameras = emptyList()
        }
        _state.value = ServiceState.NotConnected
        _connected.value = IntegrationStatus.NotConnected
    }

    /** Saves first, then remembers: a token that wasn't saved must never be the one in use. */
    private suspend fun keep(s: Saved) {
        secrets.put(SECRET_KEY, json.encodeToString(Saved.serializer(), s))
        saved = s
    }

    private fun expiry(t: RingTokens) = clock.now().toEpochMilli() + (t.expiresInSeconds - 60).coerceAtLeast(30) * 1000

    private class Access(val token: String, val expiresAt: Long)

    /**
     * An access token that works. A refresh spends the saved refresh token and gets a new one: that new token is saved
     * before the access token is handed out. [stale] is a token that just got a 401, so it isn't reused.
     */
    private suspend fun accessToken(stale: String? = null): Pair<String, String>? = refreshLock.withLock {
        val s = saved ?: return null
        val a = access
        if (a != null && a.token != stale && clock.now().toEpochMilli() < a.expiresAt) return a.token to s.hardwareId
        when (val r = api.refresh(s.refreshToken, s.hardwareId)) {
            is RingRefresh.Ok -> {
                r.tokens.refreshToken?.takeIf { it != s.refreshToken }?.let { keep(s.copy(refreshToken = it)) }
                access = Access(r.tokens.accessToken, expiry(r.tokens))
                access!!.token to s.hardwareId
            }
            RingRefresh.Rejected -> { needsSignIn(); null }
            is RingRefresh.Failed -> { lastFailure = r.why; null }
        }
    }

    @Volatile private var lastFailure: String? = null

    /**
     * Runs [call] with a working access token and this phone's session. A 401 refreshes once and asks again; a forgotten
     * session is started again once.
     */
    private suspend fun <T> authed(call: suspend (token: String, hardwareId: String) -> RingReply<T>): Outcome<T> {
        if (saved == null) return Outcome.Failure(AppError.ToolFailed("Ring", "it isn't signed in — Settings → Home"))
        var stale: String? = null
        repeat(3) {
            lastFailure = null
            val (token, hid) = accessToken(stale) ?: return if (_state.value == ServiceState.NeedsSignIn) needsSignInFailure()
                else Outcome.Failure(AppError.ToolFailed("Ring", lastFailure ?: "Ring can't be reached right now"))
            if (!sessionReady) {
                when (val s = api.startSession(token, hid)) {
                    is RingReply.Ok -> sessionReady = true
                    RingReply.Unauthorized -> { stale = token; return@repeat }
                    RingReply.NoSession -> Unit
                    is RingReply.Failed -> return Outcome.Failure(AppError.ToolFailed("Ring", s.why))
                }
            }
            when (val r = call(token, hid)) {
                is RingReply.Ok -> { _state.value = ServiceState.Connected; return Outcome.Success(r.value) }
                RingReply.Unauthorized -> stale = token
                RingReply.NoSession -> sessionReady = false
                is RingReply.Failed -> return Outcome.Failure(AppError.ToolFailed("Ring", r.why))
            }
        }
        return Outcome.Failure(AppError.ToolFailed("Ring", "Ring didn't accept Edith's sign-in just now; try again"))
    }

    private fun needsSignIn() {
        if (_state.value == ServiceState.Connected) Log.w(LogTag.Home, "Ring no longer accepts the saved sign-in")
        _state.value = ServiceState.NeedsSignIn
    }

    private fun <T> needsSignInFailure(): Outcome<T> = Outcome.Failure(AppError.ToolFailed("Ring", NEEDS_SIGN_IN))

    private suspend fun listCameras(): Outcome<List<RingCamera>> =
        authed { t, h -> api.cameras(t, h) }.also { if (it is Outcome.Success) cameras = it.value }

    private suspend fun cameraNamed(id: String): Outcome<RingCamera> {
        cameras.firstOrNull { it.id == id }?.let { return Outcome.Success(it) }
        return when (val l = listCameras()) {
            is Outcome.Failure -> l
            is Outcome.Success -> l.value.firstOrNull { it.id == id }?.let { Outcome.Success(it) }
                ?: Outcome.Failure(AppError.ToolFailed("Ring", "that camera is no longer on the account"))
        }
    }

    private fun device(c: RingCamera, now: Long) = HomeDevice(
        id = c.id, name = c.name, room = null,
        type = if (c.doorbell) DeviceType.Doorbell else DeviceType.Camera,
        capabilities = setOf(Capability.Snapshot, Capability.Events),
        backend = ID, online = !c.offline,
        state = DeviceState(battery = c.battery), stateAt = now,
    )

    private inner class Backend : HomeBackend {
        override val id = ID
        override val displayName = "Ring"
        override val status: StateFlow<IntegrationStatus> = _connected.asStateFlow()
        /** A token refresh, a session and the list can all happen before the answer. */
        override val timeoutMs: Long = 20_000
        /** A sleeping battery camera has to wake for a new picture (Jarvis waits 30 s too). */
        override val snapshotTimeoutMs: Long = 35_000

        override suspend fun devices(): Outcome<List<HomeDevice>> {
            val now = clock.now().toEpochMilli()
            return when (val l = listCameras()) {
                is Outcome.Failure -> l
                is Outcome.Success -> Outcome.Success(l.value.map { device(it, now) }.sortedBy { it.name })
            }
        }

        override suspend fun state(deviceId: String): Outcome<DeviceState> = when (val l = listCameras()) {
            is Outcome.Failure -> l
            is Outcome.Success -> l.value.firstOrNull { it.id == deviceId }?.let { Outcome.Success(DeviceState(battery = it.battery)) }
                ?: Outcome.Failure(AppError.ToolFailed("Ring", "that camera is no longer on the account"))
        }

        override suspend fun command(deviceId: String, command: DeviceCommand): Outcome<DeviceState> =
            Outcome.Failure(AppError.ToolFailed("Ring", "Ring cameras and doorbells can't be controlled from Edith (pictures and events only)"))

        override suspend fun snapshot(deviceId: String): Outcome<ByteArray> {
            val camera = when (val c = cameraNamed(deviceId)) {
                is Outcome.Failure -> return c
                is Outcome.Success -> c.value
            }
            if (camera.offline) return Outcome.Failure(AppError.ToolFailed("Ring", "${camera.name} is offline"))
            if (!camera.motionDetection) {
                return Outcome.Failure(AppError.ToolFailed("Ring", "Motion detection is off for ${camera.name}, which stops Ring taking pictures from it"))
            }
            return authed { t, h -> api.snapshot(t, h, deviceId, afterMs = clock.now().toEpochMilli() - 1000) }
        }

        override suspend fun events(deviceId: String, sinceMs: Long): Outcome<List<DeviceEvent>> {
            val camera = when (val c = cameraNamed(deviceId)) {
                is Outcome.Failure -> return c
                is Outcome.Success -> c.value
            }
            return authed { t, h -> api.events(t, h, camera, sinceMs) }
        }

        override val hasClips: Boolean get() = true

        override suspend fun clips(sinceMs: Long, untilMs: Long): Outcome<ClipsPage> {
            val list = when (val l = listCameras()) {
                is Outcome.Failure -> return l
                is Outcome.Success -> l.value
            }
            val rows = mutableListOf<RingClipRow>(); var firstFailure: Outcome.Failure? = null; var answered = 0
            for (camera in list) {
                when (val r = authed { t, h -> api.clips(t, h, camera, sinceMs, untilMs) }) {
                    is Outcome.Success -> { answered++; rows += r.value }
                    is Outcome.Failure -> if (firstFailure == null) firstFailure = r
                }
            }
            if (answered == 0 && firstFailure != null) return firstFailure
            // Whether the account has a plan isn't asked of Ring: it shows in the search (every recent event without one).
            val noPlan = rows.isNotEmpty() && rows.none { it.hadPlan }
            val clips = if (noPlan) emptyList() else rows.filter { it.hadPlan }.map { DeviceClip(it.id, ID, it.cameraName, it.at, it.kind, it.seconds) }
            return Outcome.Success(ClipsPage(clips.sortedByDescending { it.at }, noPlan))
        }

        override suspend fun clipUrl(clipId: String): Outcome<String> = authed { t, h -> api.clipUrl(t, h, clipId) }
    }

    @Serializable
    private data class Saved(val refreshToken: String, val hardwareId: String)

    companion object {
        const val ID = "ring"
        const val SECRET_KEY = "home/ring"
        const val NEEDS_SIGN_IN = "it needs signing in again — Settings → Home"
    }
}

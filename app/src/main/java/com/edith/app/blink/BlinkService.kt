package com.edith.app.blink

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.secrets.SecretStore
import com.edith.app.core.time.Clock
import com.edith.app.home.Capability
import com.edith.app.home.ClipSource
import com.edith.app.home.ClipsPage
import com.edith.app.home.DeviceClip
import com.edith.app.home.DeviceCommand
import com.edith.app.home.DeviceEvent
import com.edith.app.home.DeviceState
import com.edith.app.home.DeviceType
import com.edith.app.home.HomeBackend
import com.edith.app.home.HomeDevice
import com.edith.app.home.HomeService
import com.edith.app.home.LiveFeed
import com.edith.app.home.StreamLiveFeed
import com.edith.app.home.ServiceState
import com.edith.app.home.SignInField
import com.edith.app.home.SignInForm
import com.edith.app.home.SignInResult
import com.edith.app.tools.framework.IntegrationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Blink cameras (C29, D10): the owner's Blink account, signed in with an email, a password and a code that are typed once,
 * used once and never kept. Only Blink's refresh token, this phone's hardware id and the account's server are saved,
 * encrypted ([SecretStore] `home/blink`).
 *
 * **Fragile** (C21): there is no official API, and Blink broke every app's sign-in in July 2026 by rejecting a
 * `hardware_id` that isn't a UUID. A UUID is made once and kept. When Blink changes something, Edith says Blink can't be
 * reached or wants a new sign-in; the other services keep working. Like Ring's, the refresh token is treated as
 * rotating: each new one is saved *before* the access token it came with is used, and refreshes never overlap.
 * Live view (C32) is [BlinkLiveStream] (the stream server) plus Blink's command, kept open until the view ends.
 */
class BlinkService(
    private val secrets: SecretStore,
    private val api: BlinkApi,
    private val clock: Clock,
    /** Opens the stream server's connection for a live view; tests pass a plain socket. */
    private val liveConnect: (BlinkLive.Immis) -> java.net.Socket = BlinkLiveStream::tlsSocket,
    /** How long a live view runs before Edith ends it (Blink ends them itself after a while). */
    private val liveLimitMs: Long = 5 * 60_000L,
    /** Waits between polls; tests pass one that does not wait. (A live view always waits in real time.) */
    private val pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : HomeService {
    private val liveScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(ServiceState.NotConnected)
    override val state: StateFlow<ServiceState> = _state.asStateFlow()
    private val _connected = MutableStateFlow(IntegrationStatus.NotConnected)

    private val refreshLock = Mutex()
    @Volatile private var saved: Saved? = null
    @Volatile private var access: Access? = null
    /** A sign-in waiting for its code: Blink's cookies and CSRF token are tied to it, so the code must go to this one. */
    @Volatile private var pending: BlinkSignInSession? = null
    @Volatile private var cameras: List<BlinkCamera> = emptyList()
    /** Where each listed clip's video is, by clip id. */
    private val mediaPaths = ConcurrentHashMap<String, String>()

    override val backend: HomeBackend = Backend()

    override val form = SignInForm(
        title = "Blink",
        intro = "Sign in with the email and password of your Blink account. If Blink sends a code (a text, an email or your " +
            "authenticator app), type it and sign in again. Edith uses what you type once and doesn't keep it: only the sign-in " +
            "Blink gives back is saved, encrypted on this phone. Blink has no official way for apps like Edith, so this can " +
            "stop working whenever Blink changes something.",
        fields = listOf(
            SignInField("email", "Blink email", email = true),
            SignInField("password", "Blink password", secret = true),
            SignInField("code", "Code from Blink", onlyWhenAsked = true),
        ),
    )

    override val removeHint = "Disconnecting forgets the sign-in on this phone. Edith may stay in the Blink app's list of devices until you remove it there."

    /** At startup: picks up a saved sign-in. */
    suspend fun load() {
        val s = secrets.get(SECRET_KEY)?.let { text ->
            try { json.decodeFromString(Saved.serializer(), text) } catch (e: SerializationException) { Log.w(LogTag.Home, "The saved Blink sign-in couldn't be read"); null }
        }
        saved = s
        _state.value = if (s == null) ServiceState.NotConnected else ServiceState.Connected
        _connected.value = if (s == null) IntegrationStatus.NotConnected else IntegrationStatus.Connected
    }

    override suspend fun signIn(values: Map<String, String>): SignInResult {
        val email = values["email"]?.trim().orEmpty()
        val password = values["password"].orEmpty()
        val code = values["code"]?.trim()?.takeIf { it.isNotEmpty() }
        if (email.isEmpty() || password.isEmpty()) return SignInResult.Failed("Type your Blink email and password.")
        // A code goes to the sign-in that asked for it. Without one waiting (the app was closed) a new sign-in starts; Blink
        // sends a new code, and the one typed is tried anyway.
        val waiting = if (code != null) pending else null
        val login = if (waiting != null) api.verify(waiting, code!!) else {
            val hardwareId = saved?.hardwareId ?: UUID.randomUUID().toString().uppercase()
            when (val start = api.begin(hardwareId)) {
                is BlinkStart.Failed -> return SignInResult.Failed("${start.why}.")
                is BlinkStart.Ok -> {
                    pending = start.session
                    val first = api.signIn(start.session, email, password)
                    if (first is BlinkLogin.NeedsCode && code != null) api.verify(start.session, code) else first
                }
            }
        }
        return when (login) {
            is BlinkLogin.Ok -> finishSignIn(login.tokens)
            BlinkLogin.NeedsCode -> SignInResult.NeedsCode("Blink sent a code. Type it and sign in again.")
            BlinkLogin.WrongCode -> SignInResult.NeedsCode("That code isn't right. Check it, or wait for a new one, and try again.")
            BlinkLogin.BadLogin -> { pending = null; SignInResult.Failed("Blink says the email or password isn't right.") }
            is BlinkLogin.Failed -> { pending = null; SignInResult.Failed("${login.why}.") }
        }
    }

    private suspend fun finishSignIn(tokens: BlinkTokens): SignInResult {
        val session = pending
        val where = when (val t = api.tier(tokens.accessToken)) {
            is BlinkReply.Ok -> t.value
            is BlinkReply.Failed -> { pending = null; return SignInResult.Failed("Blink signed in but ${t.why.replaceFirstChar { it.lowercase() }}.") }
            else -> { pending = null; return SignInResult.Failed("Blink signed in but wouldn't say which account this is.") }
        }
        refreshLock.withLock {
            keep(Saved(tokens.refreshToken, session?.hardwareId ?: saved?.hardwareId ?: UUID.randomUUID().toString().uppercase(), where.tier, where.accountId))
            access = Access(tokens.accessToken, expiry(tokens))
        }
        pending = null
        _state.value = ServiceState.Connected
        _connected.value = IntegrationStatus.Connected
        Log.i(LogTag.Home, "Blink signed in")
        return SignInResult.Done
    }

    override suspend fun disconnect() {
        refreshLock.withLock {
            secrets.put(SECRET_KEY, null)
            saved = null; access = null; pending = null; cameras = emptyList(); mediaPaths.clear()
        }
        _state.value = ServiceState.NotConnected
        _connected.value = IntegrationStatus.NotConnected
    }

    /** Saves first, then remembers: a token that wasn't saved must never be the one in use. */
    private suspend fun keep(s: Saved) {
        secrets.put(SECRET_KEY, json.encodeToString(Saved.serializer(), s))
        saved = s
    }

    private fun expiry(t: BlinkTokens) = clock.now().toEpochMilli() + (t.expiresInSeconds - 60).coerceAtLeast(30) * 1000

    private class Access(val token: String, val expiresAt: Long)

    @Volatile private var lastFailure: String? = null

    /** An access token that works, refreshed when it is about to run out. [stale] just got a 401, so it isn't reused. */
    private suspend fun accessToken(stale: String? = null): Pair<String, BlinkTier>? = refreshLock.withLock {
        val s = saved ?: return null
        val where = BlinkTier(s.tier, s.accountId)
        val a = access
        if (a != null && a.token != stale && clock.now().toEpochMilli() < a.expiresAt) return a.token to where
        when (val r = api.refresh(s.refreshToken, s.hardwareId)) {
            is BlinkRefresh.Ok -> {
                if (r.tokens.refreshToken != s.refreshToken) keep(s.copy(refreshToken = r.tokens.refreshToken))
                access = Access(r.tokens.accessToken, expiry(r.tokens))
                access!!.token to where
            }
            BlinkRefresh.Rejected -> { needsSignIn(); null }
            is BlinkRefresh.Failed -> { lastFailure = r.why; null }
        }
    }

    private fun needsSignIn() {
        if (_state.value == ServiceState.Connected) Log.w(LogTag.Home, "Blink no longer accepts the saved sign-in")
        _state.value = ServiceState.NeedsSignIn
    }

    /**
     * Runs [call] with a working access token. A 401 refreshes once and asks again; "busy" (the sync module is working on an
     * earlier command) is asked again a few times, three seconds apart.
     */
    private suspend fun <T> authed(call: suspend (token: String, where: BlinkTier) -> BlinkReply<T>): Outcome<T> {
        if (saved == null) return Outcome.Failure(AppError.ToolFailed("Blink", "it isn't signed in — Settings → Home"))
        var stale: String? = null
        var busy = 0
        repeat(MAX_TRIES) {
            lastFailure = null
            val (token, where) = accessToken(stale) ?: return if (_state.value == ServiceState.NeedsSignIn) Outcome.Failure(AppError.ToolFailed("Blink", NEEDS_SIGN_IN))
                else Outcome.Failure(AppError.ToolFailed("Blink", lastFailure ?: "Blink can't be reached right now"))
            when (val r = call(token, where)) {
                is BlinkReply.Ok -> { _state.value = ServiceState.Connected; return Outcome.Success(r.value) }
                BlinkReply.Unauthorized -> stale = token
                is BlinkReply.Busy -> {
                    if (++busy >= BUSY_TRIES) return Outcome.Failure(AppError.ToolFailed("Blink", "${r.why}. Try again in a minute"))
                    pause(BUSY_WAIT_MS)
                }
                is BlinkReply.Failed -> return Outcome.Failure(AppError.ToolFailed("Blink", r.why))
            }
        }
        return Outcome.Failure(AppError.ToolFailed("Blink", "Blink didn't accept Edith's sign-in just now; try again"))
    }

    private suspend fun listCameras(): Outcome<List<BlinkCamera>> =
        authed { t, w -> api.cameras(t, w) }.also { if (it is Outcome.Success) cameras = it.value }

    private suspend fun cameraWithId(id: String, fresh: Boolean = false): Outcome<BlinkCamera> {
        if (!fresh) cameras.firstOrNull { it.id == id }?.let { return Outcome.Success(it) }
        return when (val l = listCameras()) {
            is Outcome.Failure -> l
            is Outcome.Success -> l.value.firstOrNull { it.id == id }?.let { Outcome.Success(it) }
                ?: Outcome.Failure(AppError.ToolFailed("Blink", "that camera is no longer on the account"))
        }
    }

    private fun device(c: BlinkCamera, now: Long) = HomeDevice(
        id = c.id, name = c.name, room = null,
        type = if (c.kind == BlinkKind.Doorbell) DeviceType.Doorbell else DeviceType.Camera,
        capabilities = setOf(Capability.Snapshot, Capability.Events, Capability.Live),
        backend = ID, online = c.online != false,
        state = DeviceState(), stateAt = now,
    )

    /** The media list from [sinceMs], page by page while the pages are full of clips inside the period. */
    private suspend fun mediaSince(sinceMs: Long): Outcome<List<BlinkMedia>> {
        val all = mutableListOf<BlinkMedia>()
        for (page in 1..MAX_MEDIA_PAGES) {
            val got = when (val r = authed { t, w -> api.media(t, w, sinceMs, page) }) {
                is Outcome.Failure -> return if (all.isEmpty()) r else Outcome.Success(all)
                is Outcome.Success -> r.value
            }
            all += got
            if (got.isEmpty() || got.any { it.at <= sinceMs }) break
        }
        all.forEach { mediaPaths[it.id] = it.mediaPath }
        return Outcome.Success(all)
    }

    private inner class Backend : HomeBackend {
        override val id = ID
        override val displayName = "Blink"
        override val status: StateFlow<IntegrationStatus> = _connected.asStateFlow()
        /** A token refresh and the list can both happen before the answer. */
        override val timeoutMs: Long = 25_000
        /** A battery camera takes several seconds to wake and take a picture (Jarvis waits 30 s). */
        override val snapshotTimeoutMs: Long = 45_000

        override suspend fun devices(): Outcome<List<HomeDevice>> {
            val now = clock.now().toEpochMilli()
            return when (val l = listCameras()) {
                is Outcome.Failure -> l
                is Outcome.Success -> Outcome.Success(l.value.map { device(it, now) })
            }
        }

        override suspend fun state(deviceId: String): Outcome<DeviceState> = when (val c = cameraWithId(deviceId, fresh = true)) {
            is Outcome.Failure -> c
            is Outcome.Success -> Outcome.Success(DeviceState())
        }

        override suspend fun command(deviceId: String, command: DeviceCommand): Outcome<DeviceState> =
            Outcome.Failure(AppError.ToolFailed("Blink", "Blink cameras can't be controlled from Edith (pictures, clips and motion only)"))

        /** Has the camera take a new picture, waits for Blink to say it is done, and fetches it. */
        override suspend fun snapshot(deviceId: String): Outcome<ByteArray> {
            val camera = when (val c = cameraWithId(deviceId)) {
                is Outcome.Failure -> return c
                is Outcome.Success -> c.value
            }
            if (camera.online == false) return Outcome.Failure(AppError.ToolFailed("Blink", "${camera.name} is offline"))
            val command = when (val r = authed { t, w -> api.requestSnapshot(t, w, camera) }) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            if (command != null) {
                // The camera wakes, takes the picture and uploads it; Blink says when that is done.
                val network = command.second ?: camera.networkId
                for (i in 0 until COMMAND_POLLS) {
                    pause(POLL_MS)
                    val done = authed { t, w -> api.commandDone(t, w, network, command.first) }
                    if (done is Outcome.Success && done.value) break
                    if (done is Outcome.Failure && _state.value == ServiceState.NeedsSignIn) return done
                }
            }
            // The new picture's address shows up in the list a moment after the command finishes.
            for (i in 0 until THUMBNAIL_POLLS) {
                val now = when (val c = cameraWithId(deviceId, fresh = true)) {
                    is Outcome.Failure -> return c
                    is Outcome.Success -> c.value
                }
                if (now.thumbnail != null && now.thumbnail != camera.thumbnail) return picture(now)
                pause(THUMBNAIL_WAIT_MS)
            }
            return Outcome.Failure(AppError.ToolFailed("Blink", "${camera.name} didn't send a new picture in time. It may be offline or its battery low"))
        }

        private suspend fun picture(camera: BlinkCamera): Outcome<ByteArray> {
            val path = BlinkParse.thumbnailPath(saved?.accountId.orEmpty(), camera)
                ?: return Outcome.Failure(AppError.ToolFailed("Blink", "${camera.name} has no picture yet"))
            return authed { t, w -> api.picture(t, w, path) }
        }

        /** What this camera recorded since [sinceMs], newest first: Blink's recordings are its motion events. */
        override suspend fun events(deviceId: String, sinceMs: Long): Outcome<List<DeviceEvent>> =
            when (val m = mediaSince(sinceMs)) {
                is Outcome.Failure -> m
                is Outcome.Success -> Outcome.Success(m.value.filter { it.cameraId == deviceId && it.at >= sinceMs }.map { DeviceEvent(it.at, it.kind) })
            }

        override val hasLive: Boolean get() = true

        /**
         * Opens a live view (C32): Blink starts a command and names a stream server; video then comes over that connection
         * until the command ends, [liveLimitMs] pass or the caller stops it. The camera is told when it is over.
         */
        override suspend fun startLive(deviceId: String): Outcome<LiveFeed> {
            val camera = when (val c = cameraWithId(deviceId)) {
                is Outcome.Failure -> return c
                is Outcome.Success -> c.value
            }
            if (camera.online == false) return Outcome.Failure(AppError.ToolFailed("Blink", "${camera.name} is offline"))
            val serial = camera.serial ?: return Outcome.Failure(AppError.ToolFailed("Blink", "Blink didn't give ${camera.name}'s serial number, which live view needs"))
            val answer = when (val r = authed { t, w -> api.requestLive(t, w, camera) }) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            val server = answer.server?.takeIf { it.startsWith("immis://") }?.let { BlinkLive.parseImmis(it) }
            if (server == null) {
                Log.w(LogTag.Home, "Blink offered a ${camera.kind} live view as ${answer.server?.substringBefore(':') ?: "nothing"}")
                return Outcome.Failure(AppError.ToolFailed("Blink", "Blink offered ${camera.name}'s live view in a form Edith can't play yet"))
            }
            return Outcome.Success(BlinkLiveFeed(camera, answer, server, serial))
        }

        override val hasClips: Boolean get() = true

        override suspend fun clips(sinceMs: Long, untilMs: Long): Outcome<ClipsPage> =
            when (val m = mediaSince(sinceMs)) {
                is Outcome.Failure -> m
                is Outcome.Success -> Outcome.Success(ClipsPage(
                    m.value.filter { it.at in sinceMs..untilMs }.map { DeviceClip(it.id, ID, it.cameraName, it.at, it.kind, seconds = null) },
                ))
            }

        override suspend fun clipUrl(clipId: String): Outcome<String> = when (val s = clipSource(clipId)) {
            is Outcome.Failure -> s
            is Outcome.Success -> Outcome.Success(s.value.url)
        }

        /** The video's address on the account's server, and the sign-in header the player must send with it: Blink's clips are not public. */
        override suspend fun clipSource(clipId: String): Outcome<ClipSource> {
            val path = mediaPaths[clipId] ?: return Outcome.Failure(AppError.ToolFailed("Blink", "that clip isn't in the list any more; refresh Clips"))
            val (token, where) = accessToken() ?: return Outcome.Failure(AppError.ToolFailed("Blink",
                if (_state.value == ServiceState.NeedsSignIn) NEEDS_SIGN_IN else lastFailure ?: "Blink can't be reached right now"))
            val url = api.absolute(where, path) ?: return Outcome.Failure(AppError.ToolFailed("Blink", "Blink gave an address Edith can't use"))
            return Outcome.Success(ClipSource(url.toString(), mapOf("Authorization" to "Bearer $token")))
        }
    }

    /** One running live view: the stream, a poll of Blink's command (it ends a view by ending it), and the time limit. */
    private inner class BlinkLiveFeed(camera: BlinkCamera, private val answer: BlinkParse.LiveAnswer, server: BlinkLive.Immis, serial: String) : StreamLiveFeed {
        private val channel = kotlinx.coroutines.channels.Channel<ByteArray>(64, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
        private val _ended = MutableStateFlow<String?>(null)
        override val video: kotlinx.coroutines.channels.ReceiveChannel<ByteArray> = channel
        override val ended: StateFlow<String?> = _ended.asStateFlow()
        /** The camera's own network, as Jarvis uses; the answer's `network_id` is not needed. */
        private val network = camera.networkId
        @Volatile private var job: kotlinx.coroutines.Job? = null

        init {
            job = liveScope.launch {
                val stream = launch {
                    val why = BlinkLiveStream(server, serial, liveConnect).run { channel.send(it) }
                    finish(why)
                }
                if (answer.commandId != null) launch {
                    while (true) {
                        kotlinx.coroutines.delay(answer.pollSeconds * 1000L)
                        val status = authed { t, w -> api.liveStatus(t, w, network, answer.commandId) }
                        val stopped = (status as? Outcome.Success)?.value
                        if (stopped != null) { finish("Blink ended the live view (its command stopped: $stopped)."); break }
                    }
                }
                launch { kotlinx.coroutines.delay(liveLimitMs); finish("Live view stops after a few minutes. Start it again to keep watching.") }
                stream.join()
            }
        }

        private val finished = java.util.concurrent.atomic.AtomicBoolean(false)

        private fun finish(reason: String) {
            if (!finished.compareAndSet(false, true)) return
            _ended.value = reason
            Log.i(LogTag.Home, "Blink live view ended: $reason")
            channel.close()
            liveScope.launch {
                // The camera keeps streaming until Blink is told the view is over.
                answer.commandId?.let { id -> authed { t, w -> api.endLive(t, w, network, id) } }
            }
            job?.cancel()
        }

        override fun stop() = finish("Stopped.")
    }

    @Serializable
    private data class Saved(val refreshToken: String, val hardwareId: String, val tier: String, val accountId: String)

    companion object {
        const val ID = "blink"
        const val SECRET_KEY = "home/blink"
        const val NEEDS_SIGN_IN = "it needs signing in again — Settings → Home"
        private const val MAX_TRIES = 6
        private const val BUSY_TRIES = 4
        private const val BUSY_WAIT_MS = 3_000L
        private const val COMMAND_POLLS = 20
        private const val POLL_MS = 1_000L
        private const val THUMBNAIL_POLLS = 5
        private const val THUMBNAIL_WAIT_MS = 2_000L
        private const val MAX_MEDIA_PAGES = 5
    }
}

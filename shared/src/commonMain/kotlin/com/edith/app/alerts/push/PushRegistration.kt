package com.edith.app.alerts.push

import com.edith.app.alerts.AlertChoices
import com.edith.app.jarvis.FeedResult
import com.edith.app.jarvis.JarvisApi
import com.edith.app.jarvis.feedJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/*
 * Camera alerts while Edith is closed (docs/design/ios-alerts-while-closed.md, stage 2): Jarvis sends the push, so it must know the phone's Apple token and which
 * kinds the owner switched on. This is the logic that keeps Jarvis told; the platform's part (asking for permission, getting the token) is [PushSeam].
 * Nothing here is wired into the iPhone app yet: Jarvis's side (docs/design/jarvis-push-spec.md) does not exist yet.
 */

/** What the platform gives: the notification permission and Apple's device token. */
interface PushSeam {
    /** Apple's device token as hex, once the phone has one; null before, or when registering failed. */
    val token: StateFlow<String?>

    /** True when the owner has refused notifications for Edith (in iPhone Settings, or at the prompt). */
    val denied: StateFlow<Boolean>

    /** Asks for the permission and starts getting a token. Safe to call again. */
    fun start()
}

/** The body of `POST /v1/push/register`. [env] is `production` or `sandbox`; [kinds] looks like `ring:doorbell`. */
@Serializable
data class PushRegisterBody(val token: String, val env: String, val bundleId: String, val kinds: List<String>)

/** The answer of `GET /v1/push/status`. */
@Serializable
data class PushStatus(val configured: Boolean = false, val registered: Boolean = false, val lastResult: String? = null, val lastAt: Long? = null)

/** Jarvis's push routes; [JarvisPushFeed] is the real one, tests use a fake. */
interface PushFeed {
    suspend fun register(body: PushRegisterBody): FeedResult<Unit>
    suspend fun unregister(): FeedResult<Unit>
    suspend fun status(): FeedResult<PushStatus>
}

class JarvisPushFeed(private val api: JarvisApi) : PushFeed {
    override suspend fun register(body: PushRegisterBody): FeedResult<Unit> =
        api.postNoContent(listOf("push", "register"), feedJson.encodeToString(PushRegisterBody.serializer(), body))

    override suspend fun unregister(): FeedResult<Unit> = api.postNoContent(listOf("push", "unregister"), "{}")

    override suspend fun status(): FeedResult<PushStatus> = api.getJson(listOf("push", "status"), serializer = PushStatus.serializer())
}

/** What Settings shows under the "also when Edith is closed" switch. */
sealed interface PushState {
    val words: String

    data object Off : PushState { override val words = "Off" }
    data object NoJarvis : PushState { override val words = "Connect Jarvis first (Settings → Accounts)." }
    data object PermissionDenied : PushState { override val words = "Notifications are off for Edith: allow them in iPhone Settings." }
    data object WaitingForApple : PushState { override val words = "Waiting for Apple to give this phone a push address…" }
    data object NoKinds : PushState { override val words = "Switch on at least one alert above." }
    data object Registered : PushState { override val words = "Registered with Jarvis. Alerts also arrive while Edith is closed, as long as the PC and Jarvis are on." }
    data object JarvisHasNoKey : PushState { override val words = "Jarvis has no Apple push key yet. Add it in Jarvis's Settings." }
    data class Failed(val why: String) : PushState { override val words get() = why }
}

/**
 * Keeps Jarvis told of this phone's push address and of the alert kinds that are on. It registers when everything it needs is there (the switch is on, Jarvis is
 * paired, notifications are allowed, Apple gave a token, at least one kind is on) and again whenever the token or the kinds change; it never registers the same thing
 * twice in a row, and it unregisters when the owner switches the push off or no kind is left. [run] is called once per launch (Jarvis forgets nothing, but a launch re-sends, as the spec says).
 */
class PushRegistration(
    private val seam: PushSeam,
    private val feed: PushFeed,
    /** The "also when closed" switch. */
    private val enabled: Flow<Boolean>,
    private val choices: Flow<AlertChoices>,
    /** Whether a Jarvis is paired. */
    private val paired: Flow<Boolean>,
    private val env: String,
    private val bundleId: String,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<PushState>(PushState.Off)
    val state: StateFlow<PushState> = _state.asStateFlow()

    private var lastSent: PushRegisterBody? = null
    private var started = false

    /** Starts watching; once only. */
    fun run() {
        if (started) return
        started = true
        scope.launch {
            combine(enabled, choices, paired, seam.token, seam.denied) { on, c, p, t, d -> Inputs(on, kinds(c), p, t, d) }
                .distinctUntilChanged()
                .collect { sync(it) }
        }
    }

    private data class Inputs(val on: Boolean, val kinds: List<String>, val paired: Boolean, val token: String?, val denied: Boolean)

    /** `ring:Doorbell` becomes `ring:doorbell`: the form Jarvis's spec uses. */
    private fun kinds(c: AlertChoices): List<String> = c.on.map { it.substringBefore(AlertChoices.SEP) + AlertChoices.SEP + it.substringAfter(AlertChoices.SEP).lowercase() }.sorted()

    private suspend fun sync(i: Inputs) {
        if (!i.on) {
            if (lastSent != null) { feed.unregister(); lastSent = null }
            _state.value = PushState.Off
            return
        }
        if (!i.paired) { _state.value = PushState.NoJarvis; return }
        if (i.denied) { _state.value = PushState.PermissionDenied; return }
        if (i.kinds.isEmpty()) {
            if (lastSent != null) { feed.unregister(); lastSent = null }
            _state.value = PushState.NoKinds
            return
        }
        val token = i.token
        if (token == null) {
            _state.value = PushState.WaitingForApple
            seam.start()
            return
        }
        val body = PushRegisterBody(token, env, bundleId, i.kinds)
        if (body != lastSent) {
            when (val r = feed.register(body)) {
                is FeedResult.Ok -> lastSent = body
                is FeedResult.Unreachable -> { _state.value = PushState.Failed("Jarvis can't be reached. Edith tries again next time she opens."); return }
                is FeedResult.Unauthorized -> { _state.value = PushState.NoJarvis; return }
                is FeedResult.Err -> { _state.value = PushState.Failed(r.message.ifBlank { "Jarvis refused the registration." }); return }
            }
        }
        _state.value = when (val s = feed.status()) {
            is FeedResult.Ok -> if (s.value.configured) PushState.Registered else PushState.JarvisHasNoKey
            else -> PushState.Registered   // registered a moment ago; the status line is a bonus
        }
    }
}

package com.edith.app.alerts

import com.edith.app.core.error.Outcome
import com.edith.app.core.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Camera alerts while the app is open (K4.16; the iPhone's option A, C72): the same [AlertWatcher] Android's service runs, looking every 45 s while any alert is on,
 * but with nothing in the background: iOS suspends the app, so the looking pauses with it, and what happened meanwhile is the "While you were away" list
 * ([catchUp]). New alerts are kept in [banners] for the screen to show; the owner dismisses them.
 */
class ForegroundAlerts(
    private val source: ActivitySource,
    private val prefs: AlertPrefs,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val watcher: AlertWatcher = AlertWatcher(source, clock),
) {
    private val _banners = MutableStateFlow<List<Alert>>(emptyList())
    /** The newest alerts, oldest first, until dismissed (at most [MAX_BANNERS]). */
    val banners: StateFlow<List<Alert>> = _banners.asStateFlow()

    private var job: Job? = null

    /** Starts looking (and keeps looking while any alert is switched on). Safe to call again. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                val choices = prefs.choices.first()
                if (!choices.any) {
                    delay(IDLE_MS)   // switched off: look again later for it being switched on
                    continue
                }
                val round = watcher.check(choices)
                if (round.alerts.isNotEmpty()) _banners.value = (_banners.value + round.alerts).takeLast(MAX_BANNERS)
                delay(round.nextInMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun dismiss(alert: Alert) {
        _banners.value = _banners.value - alert
    }

    fun dismissAll() {
        _banners.value = emptyList()
    }

    /**
     * What the cameras reported since the app was last opened (at most a day back, the newest [max]), for the kinds that are on. The first time there is no
     * "last opened" to go back to, so there is nothing. The moment of this opening is saved either way.
     */
    suspend fun catchUp(max: Int = 20): List<Alert> {
        val now = clock.now().toEpochMilliseconds()
        val last = prefs.lastOpened.first()
        prefs.markOpened(now)
        if (last == null) return emptyList()
        val choices = prefs.choices.first()
        val since = maxOf(last, now - MAX_AWAY_MS)
        val found = ArrayList<Alert>()
        for (backend in choices.backends) {
            val r = source.activity(backend, since) as? Outcome.Success ?: continue
            for (a in r.value) {
                val kind = AlertKind.of(a.kind)?.takeIf { choices.enabled(backend, it) } ?: continue
                if (a.at >= since) found += Alert(backend, a.device, kind, a.at, a.key)
            }
        }
        return found.distinctBy { it.key }.sortedByDescending { it.at }.take(max)
    }

    companion object {
        const val MAX_BANNERS = 5
        const val IDLE_MS = 30_000L
        /** "While you were away" reaches back at most a day. */
        const val MAX_AWAY_MS = 24 * 60 * 60_000L
    }
}

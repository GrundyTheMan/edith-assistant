package com.edith.app.alerts

import com.edith.app.core.error.Outcome
import com.edith.app.core.time.Clock

/**
 * Decides which camera events become alerts (C33), one round at a time. It holds no timer and shows nothing: the service
 * calls [check] and waits [Round.nextInMs].
 *
 * - The first successful look at a service only **learns** what is already there; nothing old is announced.
 * - Each look covers the last [WINDOW_MS], so an event the service lists late is still caught, and what was seen is
 *   remembered, so nothing is announced twice. Events of a kind that is off are remembered too: switching it on later
 *   doesn't announce the past.
 * - At most [maxPerCheck] alerts per service per look (the newest); the rest are dropped, not queued.
 * - A look that fails waits twice as long each time (up to [maxBackoffMs]) and resets after a good one.
 */
class AlertWatcher(
    private val source: ActivitySource,
    private val clock: Clock,
    private val maxPerCheck: Int = 3,
    private val intervalMs: Long = INTERVAL_MS,
    private val maxBackoffMs: Long = MAX_BACKOFF_MS,
) {
    private class Track {
        var learned = false
        var failures = 0
        var nextAt = 0L
        val seen = HashMap<String, Long>() // key -> when it happened
    }

    private val tracks = HashMap<String, Track>()

    /** [alerts] to show now (oldest first) and how long until the next look is due. */
    data class Round(val alerts: List<Alert>, val nextInMs: Long)

    suspend fun check(choices: AlertChoices): Round {
        val now = clock.now().toEpochMilli()
        tracks.keys.retainAll(choices.backends) // switched off: forget, so switching on again learns afresh
        val alerts = mutableListOf<Alert>()
        for (backend in choices.backends) {
            val track = tracks.getOrPut(backend) { Track() }
            if (now < track.nextAt) continue
            when (val r = source.activity(backend, now - WINDOW_MS)) {
                is Outcome.Failure -> {
                    track.failures++
                    track.nextAt = now + minOf(intervalMs shl minOf(track.failures, 10), maxBackoffMs)
                }
                is Outcome.Success -> {
                    track.failures = 0
                    track.nextAt = now + intervalMs
                    val fresh = r.value.filter { it.key !in track.seen }
                    fresh.forEach { track.seen[it.key] = it.at }
                    track.seen.values.removeAll { it < now - FORGET_MS }
                    if (!track.learned) {
                        track.learned = true
                    } else {
                        alerts += fresh
                            .mapNotNull { a -> AlertKind.of(a.kind)?.takeIf { choices.enabled(backend, it) }?.let { Alert(backend, a.device, it, a.at, a.key) } }
                            .sortedByDescending { it.at }
                            .take(maxPerCheck)
                    }
                }
            }
        }
        val soonest = tracks.values.minOfOrNull { it.nextAt }
        val wait = if (soonest == null) intervalMs else (soonest - now).coerceIn(MIN_WAIT_MS, maxBackoffMs)
        return Round(alerts.sortedBy { it.at }, wait)
    }

    companion object {
        /** Blink pushes only to its own app, so Edith asks every 45 s while alerts are on (C33, as Jarvis does). */
        const val INTERVAL_MS = 45_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
        const val MIN_WAIT_MS = 5_000L

        /** How far back each look reaches. */
        const val WINDOW_MS = 10 * 60_000L
        private const val FORGET_MS = 60 * 60_000L
    }
}

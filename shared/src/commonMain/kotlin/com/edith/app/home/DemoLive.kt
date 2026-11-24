package com.edith.app.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The Demo home's live view (C32), debug builds only: a short made-up MPEG transport stream, sent over and over at its own
 * pace, so the Live button and the player can be tried on the emulator with no camera. [clipSeconds] is how long the clip plays.
 */
class DemoLive(private val clip: ByteArray, private val clipSeconds: Int, private val limitMs: Long = 60_000) : StreamLiveFeed {
    private val channel = Channel<ByteArray>(32, BufferOverflow.DROP_OLDEST)
    private val _ended = MutableStateFlow<String?>(null)
    override val video: ReceiveChannel<ByteArray> = channel
    override val ended: StateFlow<String?> = _ended.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            val start = kotlin.time.TimeSource.Monotonic.markNow()
            val chunk = 188 * 7
            val perSecond = clip.size.toDouble() / clipSeconds
            while (isActive && start.elapsedNow().inWholeMilliseconds < limitMs) {
                var at = 0
                while (at + chunk <= clip.size && isActive) {
                    channel.send(clip.copyOfRange(at, at + chunk))
                    at += chunk
                    delay((chunk / perSecond * 1000).toLong().coerceAtLeast(1))
                }
            }
            finish("Live view stops after a minute in the Demo home.")
        }
    }

    private fun finish(why: String) {
        if (_ended.value != null) return
        _ended.value = why
        channel.close()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    override fun stop() = finish("Stopped.")
}

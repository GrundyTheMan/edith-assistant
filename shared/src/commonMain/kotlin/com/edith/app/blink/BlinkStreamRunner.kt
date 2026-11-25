package com.edith.app.blink

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/** One open byte connection (a TLS socket) the live view talks over; the platform provides it. */
interface StreamTransport {
    /** Waits for some bytes (at most [max]); null at the end of the stream. Throws when the connection breaks. */
    suspend fun read(max: Int): ByteArray?
    suspend fun write(bytes: ByteArray)
    fun close()
}

/** Opens a TLS connection to Blink's stream server. The certificate is NOT checked (see [BlinkLiveRunner]). */
fun interface StreamTransportFactory {
    suspend fun open(host: String, port: Int): StreamTransport
}

/**
 * Blink's live stream over any [StreamTransport] (C32, K4.17): sends the handshake, keeps the stream alive (statistics each second, a keep-alive every
 * ten) and hands the video on. The same logic as Android's `BlinkLiveStream`, written once for the iPhone and testable with a fake transport.
 */
class BlinkStreamRunner(
    private val factory: StreamTransportFactory,
    private val quietMs: Long = 20_000,
    private val tickMs: Long = 1_000,
) : BlinkLiveRunner {
    override suspend fun run(server: BlinkLive.Immis, serial: String, onVideo: suspend (ByteArray) -> Unit): String {
        val started = TimeSource.Monotonic.markNow()
        val transport = try { factory.open(server.host, server.port) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(LogTag.Home, "Blink live view: couldn't connect to the stream server", e)
            return "The live view connection failed (couldn't connect: ${describe(e)})"
        }
        try {
            transport.write(BlinkLive.handshake(serial, server))
            return coroutineScope {
                val ticker = launch {
                    var ticks = 0
                    var sequence = 0L
                    while (isActive) {
                        delay(tickMs)
                        try {
                            transport.write(BlinkLive.latencyStats())
                            if (ticks++ % 10 == 0) transport.write(BlinkLive.keepAlive(++sequence))
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { return@launch }
                    }
                }
                var bytes = 0L
                val reader = BlinkLive.FrameReader()
                val reason = try {
                    var why: String? = null
                    while (why == null && isActive) {
                        val got = withTimeoutOrNull(quietMs) { Got(transport.read(16 * 1024)) }
                        if (got == null) why = "The camera stopped sending video."
                        else if (got.data == null) why = if (bytes == 0L) "Blink's stream server closed the connection before any video arrived." else "Blink's stream server closed the connection after ${bytes / 1024} KB of video."
                        else for (chunk in reader.push(got.data, got.data.size)) { bytes += chunk.size; onVideo(chunk) }
                    }
                    why ?: "Stopped."
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    val seconds = started.elapsedNow().inWholeSeconds
                    Log.w(LogTag.Home, "Blink live view: the stream broke after $seconds s and ${bytes / 1024} KB", e)
                    "The live view connection failed (${describe(e)}, after $seconds s and ${bytes / 1024} KB of video)"
                }
                ticker.cancel()
                reason
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return "The live view connection failed (${describe(e)})"
        } finally {
            runCatching { transport.close() }
        }
    }

    override fun closedEarly(reason: String, seconds: Long): Boolean = seconds < EARLY_S && reason.startsWith("Blink's stream server closed the connection")

    private class Got(val data: ByteArray?)

    private companion object {
        const val EARLY_S = 10
        fun describe(e: Throwable): String = "${e::class.simpleName}${e.message?.takeIf { it.isNotBlank() }?.let { ": " + it.take(80) }.orEmpty()}"
    }
}

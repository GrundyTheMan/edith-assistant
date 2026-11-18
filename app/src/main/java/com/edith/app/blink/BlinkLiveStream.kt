package com.edith.app.blink

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * One open connection to Blink's stream server (C32): sends the handshake, keeps the stream alive and hands the video on.
 * [run] returns why it ended; cancelling it closes the connection.
 */
class BlinkLiveStream(
    private val server: BlinkLive.Immis,
    private val serial: String,
    private val connect: (BlinkLive.Immis) -> Socket = ::tlsSocket,
) {
    /** Runs until the server ends the stream, it goes quiet for 20 s, or the caller cancels. Every video chunk goes to [onVideo]. */
    suspend fun run(onVideo: suspend (ByteArray) -> Unit): String = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val socket = try { connect(server) } catch (e: IOException) {
            Log.w(LogTag.Home, "Blink live view: couldn't connect to the stream server", e)
            return@withContext "The live view connection failed (couldn't connect: ${describe(e)})"
        }
        try {
            socket.soTimeout = QUIET_MS
            val out = socket.getOutputStream()
            out.write(BlinkLive.handshake(serial, server)); out.flush()
            coroutineScope {
                val ticker = launch {
                    var ticks = 0
                    var sequence = 0L
                    while (isActive) {
                        delay(1_000)
                        try {
                            out.write(BlinkLive.latencyStats())
                            if (ticks++ % 10 == 0) out.write(BlinkLive.keepAlive(++sequence))
                            out.flush()
                        } catch (e: IOException) { return@launch }
                    }
                }
                var bytes = 0L
                val reader = BlinkLive.FrameReader()
                val buffer = ByteArray(16 * 1024)
                val input = socket.getInputStream()
                val reason = try {
                    var why: String? = null
                    while (why == null && isActive) {
                        val n = input.read(buffer)
                        if (n < 0) why = if (bytes == 0L) "Blink's stream server closed the connection before any video arrived." else "Blink's stream server closed the connection after ${bytes / 1024} KB of video."
                        else for (chunk in reader.push(buffer, n)) { bytes += chunk.size; onVideo(chunk) }
                    }
                    why ?: "Stopped."
                } catch (e: SocketTimeoutException) { "The camera stopped sending video." }
                catch (e: CancellationException) { throw e }
                catch (e: IOException) {
                    if (!isActive) "Stopped."
                    else {
                        val seconds = (System.nanoTime() - started) / 1_000_000_000
                        Log.w(LogTag.Home, "Blink live view: the stream broke after $seconds s and ${bytes / 1024} KB", e)
                        "The live view connection failed (${describe(e)}, after $seconds s and ${bytes / 1024} KB of video)"
                    }
                }
                ticker.cancel()
                reason
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val QUIET_MS = 20_000
        private const val EARLY_S = 10

        /** Whether the server dropped the connection soon after it began, which Blink does now and then and a second try gets past. */
        fun closedEarly(reason: String, seconds: Long) = seconds < EARLY_S && reason.startsWith("Blink's stream server closed the connection")

        /** What went wrong, for the message and the log: the kind of error and its text, short. */
        internal fun describe(e: Throwable): String = "${e.javaClass.simpleName}${e.message?.takeIf { it.isNotBlank() }?.let { ": " + it.take(80) }.orEmpty()}"

        /**
         * Blink's stream servers are reached by address, with certificates that don't name them, so the certificate isn't
         * checked (as blinkpy and Jarvis don't). Only the camera's video and its serial number cross this connection, never
         * a token or password, and the exception is this one socket's.
         */
        fun tlsSocket(server: BlinkLive.Immis): Socket {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
            return (context.socketFactory.createSocket(server.host, server.port) as SSLSocket).also { it.startHandshake() }
        }
    }
}

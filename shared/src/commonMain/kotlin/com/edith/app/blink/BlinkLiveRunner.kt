package com.edith.app.blink

/**
 * Runs one connection to Blink's stream server (C32), which is the platform's: a TLS socket to a server whose certificate does not name it, so the certificate
 * is not checked (as blinkpy and Jarvis do not). Android's is `BlinkLiveStream`, the iPhone's is `BlinkStreamRunner` (K4.17; snapshots,
 * events and clips too). [run] returns why the stream ended; cancelling it closes the connection.
 */
interface BlinkLiveRunner {
    suspend fun run(server: BlinkLive.Immis, serial: String, onVideo: suspend (ByteArray) -> Unit): String

    /** Whether the server dropped the connection soon after it began, which Blink does now and then and a second try gets past. */
    fun closedEarly(reason: String, seconds: Long): Boolean
}

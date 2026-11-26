package com.edith.app.blink

import com.edith.app.core.net.HttpClients
import com.edith.app.core.secrets.SecretStore
import com.edith.app.core.time.Clock
import okhttp3.OkHttpClient
import java.net.Socket

/**
 * Android's Blink (K4.13): the shared [BlinkApi] and [BlinkService] over OkHttp, with what only Android has: an IPv4-only route (Blink answered 406 on the
 * owner's phone until it was used, 2026-11-15) and the stream server's TLS socket for live view. Functions named for the platform, so no caller of the
 * shared classes changes.
 */
fun AndroidBlinkApi(http: Lazy<OkHttpClient>, endpoints: BlinkEndpoints = BlinkEndpoints()): BlinkApi =
    BlinkApi(
        lazy { HttpClients.over(noRedirects(http.value)) }, endpoints,
        ipv4Client = { HttpClients.over(noRedirects(http.value.newBuilder().dns(BlinkIpv4.ONLY).build())) },
    )

/** The shared [BlinkApi] decides redirects step by step (Blink's sign-in ends on an address of its app's own), so its base client follows none. */
private fun noRedirects(client: OkHttpClient): OkHttpClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

/** The shared service with Android's live view: the stream over [BlinkLiveStream], whose connection [liveConnect] opens (tests pass a plain socket). */
fun AndroidBlinkService(
    secrets: SecretStore,
    api: BlinkApi,
    clock: Clock,
    liveConnect: (BlinkLive.Immis) -> Socket = BlinkLiveStream::tlsSocket,
    liveLimitMs: Long = 5 * 60_000L,
    liveRetryWaitMs: Long = 3_000L,
    liveStatusWaitMs: Long = 1_500L,
    pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
): BlinkService = BlinkService(secrets, api, clock, AndroidBlinkLive(liveConnect), liveLimitMs, liveRetryWaitMs, liveStatusWaitMs, pause)

/** Android's [BlinkLiveRunner]: [BlinkLiveStream] over a socket. */
class AndroidBlinkLive(private val connect: (BlinkLive.Immis) -> Socket) : BlinkLiveRunner {
    override suspend fun run(server: BlinkLive.Immis, serial: String, onVideo: suspend (ByteArray) -> Unit): String =
        BlinkLiveStream(server, serial, connect).run(onVideo)

    override fun closedEarly(reason: String, seconds: Long): Boolean = BlinkLiveStream.closedEarly(reason, seconds)
}

object BlinkIpv4 {
    /** Only the IPv4 addresses of a host; with none, the lookup fails like an unknown host. */
    val ONLY = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> =
            okhttp3.Dns.SYSTEM.lookup(hostname).filterIsInstance<java.net.Inet4Address>().ifEmpty { throw java.net.UnknownHostException("$hostname has no IPv4 address") }
    }
}

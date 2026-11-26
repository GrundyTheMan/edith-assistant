@file:OptIn(ExperimentalForeignApi::class)

package com.edith.app.blink.hls

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import com.edith.app.core.util.PlatformLock
import com.edith.app.core.util.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.posix.AF_INET

import platform.posix.POLLIN
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar

/**
 * Serves one [HlsLiveSession] over plain HTTP on this phone's loopback address (K4.17), so Apple's player fetches `http://127.0.0.1:<port>/live.m3u8` and the
 * segments like any stream. Only this phone can reach it. The first version served the playlist through the player's resource loader on a made-up address
 * and the player refused it; a real address is what the player expects. Written without a Mac to try it on: CI compiles it; the owner's phone is the first real run.
 */
class LocalHlsServer(private val session: HlsLiveSession) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = PlatformLock()
    private val seen = mutableListOf<String>()

    /** The port the server listens on; set by [start]. */
    var port: Int = 0
        private set

    /** The last few requests the player made and what they were answered with, for the screen while this is proven on a phone. */
    fun requests(): String = lock.synchronized { seen.takeLast(4).joinToString("; ") }

    /** Starts listening on a free port of 127.0.0.1. Returns false when the socket could not be made. */
    fun start(): Boolean {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) return false
        val ok = memScoped {
            val one = alloc<IntVar>().also { it.value = 1 }
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            val addr = alloc<sockaddr_in>()
            addr.sin_len = sizeOf<sockaddr_in>().convert()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = 0u                       // any free port
            addr.sin_addr.s_addr = 0x0100007fu       // 127.0.0.1 in network order (this phone is little-endian)
            if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0 || listen(fd, 8) != 0) return@memScoped false
            val len = alloc<socklen_tVar>().also { it.value = sizeOf<sockaddr_in>().convert() }
            if (getsockname(fd, addr.ptr.reinterpret(), len.ptr) != 0) return@memScoped false
            val raw = addr.sin_port.toInt()
            port = ((raw shr 8) and 0xff) or ((raw and 0xff) shl 8)
            true
        }
        if (!ok) { close(fd); return false }
        scope.launch { acceptLoop(fd) }
        return true
    }

    /** Stops listening and answering. */
    fun stop() {
        scope.cancel()
    }

    private suspend fun acceptLoop(fd: Int) {
        try {
            while (scope.isActive) {
                // Waiting with a timeout (not a blocking accept) lets stop() end the loop and the socket be closed here.
                val ready = memScoped {
                    val p = alloc<pollfd>()
                    p.fd = fd
                    p.events = POLLIN.convert()
                    poll(p.ptr, 1u, 500)
                }
                if (ready <= 0) continue
                val client = accept(fd, null, null)
                if (client < 0) continue
                scope.launch(Dispatchers.Default) { serve(client) }
            }
        } finally {
            close(fd)
        }
    }

    private suspend fun serve(client: Int) {
        try {
            memScoped {
                val one = alloc<IntVar>().also { it.value = 1 }
                setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())
            }
            val request = readRequest(client) ?: return
            val path = request.lineSequence().firstOrNull()?.split(" ")?.getOrNull(1) ?: return
            when {
                path.endsWith(".m3u8") -> {
                    var text = session.playlist()
                    var waited = 0
                    while (text == null && waited < 20_000 && scope.isActive) { delay(100); waited += 100; text = session.playlist() }
                    if (text == null) reply(client, path, 404, "text/plain", "No video yet".encodeToByteArray())
                    else reply(client, path, 200, "application/vnd.apple.mpegurl", text.encodeToByteArray())
                }
                path.endsWith(".ts") -> {
                    val bytes = session.segment(path.substringAfterLast("seg").removeSuffix(".ts").toLongOrNull() ?: -1)
                    if (bytes == null) reply(client, path, 404, "text/plain", "No such segment".encodeToByteArray())
                    else reply(client, path, 200, "video/mp2t", bytes)
                }
                else -> reply(client, path, 404, "text/plain", "Not found".encodeToByteArray())
            }
        } finally {
            close(client)
        }
    }

    /** The request's head, up to the blank line; null when the client sent nothing. */
    private suspend fun readRequest(client: Int): String? {
        val out = StringBuilder()
        val buffer = ByteArray(2048)
        var tries = 0
        while (!out.contains("\r\n\r\n") && out.length < 16_384 && tries < 200) {
            val n = buffer.usePinned { recv(client, it.addressOf(0), buffer.size.convert(), 0x80 /* MSG_DONTWAIT on Apple platforms */).toInt() }
            if (n > 0) out.append(buffer.decodeToString(0, n))
            else if (n == 0) break
            else { delay(25); tries++ }
        }
        return out.toString().takeIf { it.isNotEmpty() }
    }

    private suspend fun reply(client: Int, path: String, status: Int, type: String, body: ByteArray) {
        note("$path $status")
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
        val all = head.encodeToByteArray() + body
        var sent = 0
        var tries = 0
        while (sent < all.size && tries < 400) {
            val n = all.usePinned { send(client, it.addressOf(sent), (all.size - sent).convert(), 0x80 /* MSG_DONTWAIT on Apple platforms */).toInt() }
            if (n > 0) { sent += n; tries = 0 } else { delay(25); tries++ }
        }
    }

    private fun note(line: String) { lock.synchronized { seen += line } }

}

package com.edith.app.blink

import com.edith.app.icloud.mail.NsStreamTransport

/**
 * The iPhone's connection to Blink's stream server (K4.17): Apple's streams with TLS, certificate not checked (the server is reached by address and its
 * certificate does not name it; only video and a serial number cross it). Written without a Mac to try it on: CI compiles it; the owner's phone is the first real run.
 */
class NsBlinkTransportFactory : StreamTransportFactory {
    override suspend fun open(host: String, port: Int): StreamTransport {
        // The runner times out a silent camera itself, so the stream's own wait is long.
        val ns = NsStreamTransport.open(host, port, tls = true, timeoutMs = 10 * 60_000L, trustAnyCertificate = true, peer = "Blink's stream server")
        return object : StreamTransport {
            override suspend fun read(max: Int): ByteArray? = ns.readSome(max)
            override suspend fun write(bytes: ByteArray) = ns.write(bytes)
            override fun close() = ns.close()
        }
    }
}

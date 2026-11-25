@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.edith.app.jarvis

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.URLProtocol
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.io.IOException
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.Security.SecCertificateCopyData
import platform.Security.SecCertificateRef
import platform.Security.SecTrustGetCertificateAtIndex
import platform.Security.SecTrustGetCertificateCount
import platform.Security.SecTrustRef
import platform.posix.memcpy

/**
 * The iPhone's engine hook of the shared Jarvis client (K4.5; Android's is `OkHttpPinnedHttp`): a Darwin Ktor client that is https-only and that
 * trusts the PC's self-signed certificate only when the [PinCheck] accepts its first certificate, instead of asking the system's certificate
 * authorities. The system's hostname check is part of that trust decision, so a pin match alone settles it, as on Android: the address is an IP
 * that can change and the pin is what proves it is the owner's PC. With no pin yet (pairing) the check only reads the fingerprint.
 * Timeouts match Android's: 8 s to connect, 30 s of silence and 40 s for a call unless a request says otherwise.
 */
class DarwinPinnedHttp : PinnedHttp {
    override fun client(check: PinCheck, readTimeoutS: Long?, callTimeoutS: Long?): HttpClient {
        val read = readTimeoutS ?: 30L
        val call = callTimeoutS ?: 40L
        return HttpClient(Darwin) {
            engine {
                configureSession {
                    timeoutIntervalForRequest = read.toDouble()
                    timeoutIntervalForResource = call.toDouble()
                }
                configureRequest { setTimeoutInterval(read.toDouble()) }
                handleChallenge { _, _, challenge, completionHandler ->
                    if (challenge.protectionSpace.authenticationMethod != NSURLAuthenticationMethodServerTrust) {
                        completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
                        return@handleChallenge
                    }
                    val trust = challenge.protectionSpace.serverTrust
                    val leaf = trust?.let { firstCertificate(it) }
                    if (trust != null && leaf != null && check.accepts(leaf)) {
                        completionHandler(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(trust))
                    } else {
                        // No certificate, or not the paired PC's: the connection is refused (PinCheck remembers why).
                        completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
                    }
                }
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 8_000
                socketTimeoutMillis = read * 1_000
                requestTimeoutMillis = call * 1_000
            }
            install(HttpsOnly)
            followRedirects = false
            expectSuccess = false
        }
    }

    /** The DER bytes of the server's first (leaf) certificate, or null if the system has none to give. */
    @Suppress("DEPRECATION")
    private fun firstCertificate(trust: SecTrustRef): ByteArray? {
        if (SecTrustGetCertificateCount(trust) < 1) return null
        return SecTrustGetCertificateAtIndex(trust, 0)?.let { der(it) }
    }

    private fun der(certificate: SecCertificateRef): ByteArray? {
        val data = SecCertificateCopyData(certificate) ?: return null
        try {
            val size = CFDataGetLength(data).toInt()
            val bytes = CFDataGetBytePtr(data) ?: return null
            val out = ByteArray(size)
            if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, size.toULong()) }
            return out
        } finally {
            CFRelease(data)
        }
    }
}

/** Jarvis is reached over https only (C37): anything else is refused before it is sent. */
private val HttpsOnly = createClientPlugin("JarvisHttpsOnly") {
    onRequest { request, _ ->
        if (request.url.protocol != URLProtocol.HTTPS) throw IOException("Refused a non-https request to Jarvis.")
    }
}

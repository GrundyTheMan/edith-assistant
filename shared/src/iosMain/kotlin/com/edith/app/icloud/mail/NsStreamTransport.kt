@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.edith.app.icloud.mail

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import kotlinx.io.IOException
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.CoreFoundation.CFReadStreamRefVar
import platform.CoreFoundation.CFReadStreamSetProperty
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStreamCreatePairWithSocketToHost
import platform.CoreFoundation.CFWriteStreamRefVar
import platform.CoreFoundation.CFWriteStreamSetProperty
import platform.CoreFoundation.CFReadStreamRef
import platform.CoreFoundation.CFWriteStreamRef
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStreamPropertyShouldCloseNativeSocket
import platform.Foundation.NSInputStream
import platform.Foundation.NSOutputStream
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.NSStreamSocketSecurityLevelKey
import platform.Foundation.NSStreamSocketSecurityLevelNegotiatedSSL
import platform.Foundation.NSStreamStatusAtEnd
import platform.Foundation.NSStreamStatusClosed
import platform.Foundation.NSStreamStatusError
import platform.Foundation.NSStreamStatusNotOpen
import platform.Foundation.NSStreamStatusOpening
import kotlin.time.TimeSource

/**
 * A mail connection on Apple's own streams (K4.10): `NSInputStream` and `NSOutputStream` to the host, with Apple's TLS (`NegotiatedSSL`), which checks the
 * server's certificate chain and host name against the system's trust store. TLS from the first byte for IMAP, or switched on in the middle by
 * [startTls] after SMTP's `220 Ready to start TLS`. The streams are not scheduled on a run loop: reads and writes poll with a short delay so a silent server
 * ends in an [IOException] after [timeoutMs] instead of hanging. Written without a Mac to try it on: CI compiles it; the owner's phone is the first real run.
 */
class NsStreamTransport private constructor(
    private val input: NSInputStream,
    private val output: NSOutputStream,
    private val timeoutMs: Long,
) : MailTransport {
    private val pending = ArrayList<Byte>()

    override suspend fun readLine(): String? {
        while (true) {
            val at = pending.indexOf('\n'.code.toByte())
            if (at >= 0) {
                val line = ByteArray(at) { pending[it] }
                repeat(at + 1) { pending.removeAt(0) }
                return Mime.rawOf(line).trimEnd('\r')
            }
            if (!fill()) {
                if (pending.isEmpty()) return null
                val rest = Mime.rawOf(pending.toByteArray()); pending.clear()
                return rest
            }
        }
    }

    override suspend fun readBytes(count: Int): ByteArray {
        while (pending.size < count) if (!fill()) throw IOException("The mail server closed the connection.")
        val out = ByteArray(count) { pending[it] }
        repeat(count) { pending.removeAt(0) }
        return out
    }

    override suspend fun write(bytes: ByteArray) {
        var sent = 0
        val deadline = TimeSource.Monotonic.markNow() + timeoutMs.let { kotlin.time.Duration.parse("${it}ms") }
        while (sent < bytes.size) {
            if (output.hasSpaceAvailable) {
                val n = bytes.usePinned { output.write(it.addressOf(sent).reinterpret<UByteVar>(), (bytes.size - sent).toULong()) }
                if (n < 0) throw IOException("Writing to the mail server failed.")
                sent += n.toInt()
                continue
            }
            check(output.streamStatus)
            if (deadline.hasPassedNow()) throw IOException("The mail server didn't take the data in time.")
            delay(10)
        }
    }

    override suspend fun startTls() {
        pending.clear()
        input.setProperty(NSStreamSocketSecurityLevelNegotiatedSSL, forKey = NSStreamSocketSecurityLevelKey)
        output.setProperty(NSStreamSocketSecurityLevelNegotiatedSSL, forKey = NSStreamSocketSecurityLevelKey)
    }

    override fun close() {
        input.close()
        output.close()
    }

    /** Reads what is available into [pending]; false at the end of the stream. */
    private suspend fun fill(): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + kotlin.time.Duration.parse("${timeoutMs}ms")
        while (true) {
            if (input.hasBytesAvailable) {
                val n = memScoped {
                    val buffer = allocArray<UByteVar>(CHUNK)
                    val read = input.read(buffer, CHUNK.toULong())
                    if (read > 0) for (i in 0 until read.toInt()) pending += buffer[i].toByte()
                    read
                }
                if (n > 0) return true
                if (n == 0L) return false
                throw IOException("Reading from the mail server failed.")
            }
            when (input.streamStatus) {
                NSStreamStatusAtEnd, NSStreamStatusClosed -> return false
                else -> check(input.streamStatus)
            }
            if (deadline.hasPassedNow()) throw IOException("The mail server didn't answer in time.")
            delay(10)
        }
    }

    private fun check(status: ULong) {
        if (status == NSStreamStatusError) throw IOException("The secure connection to the mail server failed.")
    }

    companion object {
        private const val CHUNK = 4096

        /** Connects to [host]:[port]; [tls] true: TLS from the first byte. Throws [IOException] when it cannot. */
        suspend fun open(host: String, port: Int, tls: Boolean, timeoutMs: Long = 20_000): NsStreamTransport {
            // Foundation's own helper for this is not on the iPhone's SDK, so the pair is made by CoreFoundation and used as the streams they are (toll-free).
            var read: CFReadStreamRef? = null
            var write: CFWriteStreamRef? = null
            memScoped {
                val r = alloc<CFReadStreamRefVar>()
                val w = alloc<CFWriteStreamRefVar>()
                val name = CFBridgingRetain(NSString.create(string = host))
                CFStreamCreatePairWithSocketToHost(null, name?.reinterpret(), port.toUInt(), r.ptr, w.ptr)
                CFRelease(name)
                read = r.value
                write = w.value
            }
            // Close the system's socket when the stream is closed, or it would be left open.
            CFReadStreamSetProperty(read, kCFStreamPropertyShouldCloseNativeSocket, kCFBooleanTrue)
            CFWriteStreamSetProperty(write, kCFStreamPropertyShouldCloseNativeSocket, kCFBooleanTrue)
            val input = CFBridgingRelease(read) as? NSInputStream ?: throw IOException("Edith couldn't start a connection to $host.")
            val output = CFBridgingRelease(write) as? NSOutputStream ?: throw IOException("Edith couldn't start a connection to $host.")
            if (tls) {
                input.setProperty(NSStreamSocketSecurityLevelNegotiatedSSL, forKey = NSStreamSocketSecurityLevelKey)
                output.setProperty(NSStreamSocketSecurityLevelNegotiatedSSL, forKey = NSStreamSocketSecurityLevelKey)
            }
            input.open()
            output.open()
            val deadline = TimeSource.Monotonic.markNow() + kotlin.time.Duration.parse("${timeoutMs}ms")
            while (input.streamStatus == NSStreamStatusNotOpen || input.streamStatus == NSStreamStatusOpening || output.streamStatus == NSStreamStatusOpening) {
                if (deadline.hasPassedNow()) { input.close(); output.close(); throw IOException("The mail server didn't answer in time.") }
                delay(10)
            }
            if (input.streamStatus == NSStreamStatusError || output.streamStatus == NSStreamStatusError) {
                input.close(); output.close()
                throw IOException("Edith couldn't connect to $host.")
            }
            return NsStreamTransport(input, output, timeoutMs)
        }
    }
}

/** The iPhone's [MailTransportFactory]: Apple's streams and TLS. */
class NsStreamTransportFactory : MailTransportFactory {
    override suspend fun open(host: String, port: Int, tls: Boolean): MailTransport = NsStreamTransport.open(host, port, tls)
}

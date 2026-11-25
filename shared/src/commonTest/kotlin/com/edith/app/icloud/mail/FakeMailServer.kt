package com.edith.app.icloud.mail

/**
 * A pretend mail server for tests: every write the client makes is given to [respond], whose answer (raw text, line endings included) is what the
 * client reads next. Reading runs over what has been queued; [startTls] only records that it happened, with the writes made before and after it.
 */
class FakeMailServer(private val greeting: String? = null, private val respond: (String) -> String?) : MailTransport {
    private val input = ArrayList<Byte>()
    val written = ArrayList<String>()
    var tlsStartedAfterWrites: Int? = null
        private set
    var closed = false
        private set

    init { greeting?.let { queue(it) } }

    private fun queue(text: String) { Mime.bytesOf(text).forEach { input += it } }

    override suspend fun readLine(): String? {
        if (input.isEmpty()) return null
        val sb = StringBuilder()
        while (input.isNotEmpty()) {
            val c = (input.removeAt(0).toInt() and 0xff).toChar()
            if (c == '\n') break
            if (c != '\r') sb.append(c)
        }
        return sb.toString()
    }

    override suspend fun readBytes(count: Int): ByteArray {
        require(input.size >= count) { "the server queued only ${input.size} bytes, ${count} wanted" }
        return ByteArray(count) { input.removeAt(0) }
    }

    override suspend fun write(bytes: ByteArray) {
        val text = Mime.rawOf(bytes)
        written += text
        respond(text)?.let { queue(it) }
    }

    override suspend fun startTls() { tlsStartedAfterWrites = written.size }

    override fun close() { closed = true }
}

/** A factory that hands out [server] for whatever is asked, and remembers what was asked. */
class FakeMailFactory(private val servers: Map<Int, FakeMailServer>) : MailTransportFactory {
    val opened = ArrayList<Triple<String, Int, Boolean>>()
    override suspend fun open(host: String, port: Int, tls: Boolean): MailTransport {
        opened += Triple(host, port, tls)
        return servers[port] ?: throw kotlinx.io.IOException("no server on $port")
    }
}

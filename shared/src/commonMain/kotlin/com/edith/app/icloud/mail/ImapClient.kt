package com.edith.app.icloud.mail

import kotlinx.io.IOException

/**
 * A byte connection to a mail server (K4.10): TLS from the start for IMAP, or upgraded in the middle (STARTTLS) for SMTP. Lines and bytes are exchanged
 * as ISO-8859-1 so the raw message survives (see [Mime]). The iPhone's is `NsStreamTransport` (Apple's TLS, with Apple's certificate checks); tests use
 * a scripted fake.
 */
interface MailTransport {
    /** The next line without its line ending, or null when the server closed the connection. */
    suspend fun readLine(): String?
    /** Exactly [count] bytes (an IMAP literal). */
    suspend fun readBytes(count: Int): ByteArray
    suspend fun write(bytes: ByteArray)
    /** Starts TLS on the open connection (after SMTP's `220 Ready to start TLS`). */
    suspend fun startTls()
    fun close()
}

fun interface MailTransportFactory {
    /** Connects to [host]:[port]; [tls] true: TLS from the first byte. Throws [IOException] when it cannot connect. */
    suspend fun open(host: String, port: Int, tls: Boolean): MailTransport
}

/** An IMAP or SMTP failure: [passwordRejected] when the server refused the login. */
class MailException(val why: String, val passwordRejected: Boolean = false) : Exception(why)

/** One message's headers as `SEARCH` and `FETCH` give them. */
class ImapHeaderRow(val uid: Long, val flags: Set<String>, val internalDate: String?, val header: String)

/**
 * The part of IMAP (RFC 3501) Edith needs, over a [MailTransport]: login, read-only `EXAMINE`, `UID SEARCH`, `UID FETCH` of headers and whole messages with
 * `BODY.PEEK` (so reading never sets `\Seen`), `LIST` and `APPEND`. One connection per operation; nothing is logged. A response's literals (`{n}`) are read
 * in full and put in the text as a placeholder, so the replies parse with plain patterns.
 */
class ImapClient(private val t: MailTransport) {
    private var tagCounter = 0

    private class Untagged(val text: String, val literals: List<String>)
    private class Reply(val untagged: List<Untagged>, val ok: Boolean, val text: String)

    /** The server's greeting; throws unless it is `* OK`. */
    suspend fun greeting() {
        val line = t.readLine() ?: throw MailException("iCloud Mail closed the connection.")
        if (!line.startsWith("* OK") && !line.startsWith("* PREAUTH")) throw MailException("iCloud Mail didn't greet Edith properly.")
    }

    /** `LOGIN`; a `NO` is the server refusing the name or password. */
    suspend fun login(user: String, password: String) {
        val r = command("LOGIN ${quote(user)} ${quote(password)}")
        if (!r.ok) throw MailException("The password was refused.", passwordRejected = true)
    }

    class Mailbox(val uidValidity: Long, val exists: Int)

    /** `EXAMINE` (read-only): nothing done in it can set a flag. */
    suspend fun examine(folder: String): Mailbox {
        val r = command("EXAMINE ${quote(folder)}")
        if (!r.ok) throw MailException("iCloud Mail couldn't open $folder.")
        var validity = 0L
        var exists = 0
        for (u in r.untagged) {
            Regex("""\[UIDVALIDITY (\d+)\]""").find(u.text)?.let { validity = it.groupValues[1].toLong() }
            Regex("""^\* (\d+) EXISTS""").find(u.text)?.let { exists = it.groupValues[1].toInt() }
        }
        return Mailbox(validity, exists)
    }

    /** `UID SEARCH` with [criteria] (already in IMAP syntax); the UIDs found, oldest first. */
    suspend fun uidSearch(criteria: String): List<Long> {
        val r = command("UID SEARCH $criteria")
        if (!r.ok) throw MailException("iCloud Mail couldn't search.")
        return r.untagged.filter { it.text.startsWith("* SEARCH") }
            .flatMap { it.text.removePrefix("* SEARCH").trim().split(' ').mapNotNull(String::toLongOrNull) }
    }

    /** The newest [count] UIDs: `UID SEARCH ALL` is cheap for a mailbox of this size, and the highest UIDs are the newest. */
    suspend fun newestUids(count: Int): List<Long> = uidSearch("ALL").takeLast(count)

    /** The headers Edith lists ([HEADER_FIELDS]), the flags and the date for each of [uids]. */
    suspend fun fetchHeaders(uids: List<Long>): List<ImapHeaderRow> {
        if (uids.isEmpty()) return emptyList()
        val r = command("UID FETCH ${uids.joinToString(",")} (UID FLAGS INTERNALDATE BODY.PEEK[HEADER.FIELDS ($HEADER_FIELDS)])")
        if (!r.ok) throw MailException("iCloud Mail couldn't list those emails.")
        return r.untagged.mapNotNull { u -> rowOf(u) }
    }

    /** The whole message with that UID (`BODY.PEEK[]`), as a raw string, or null when it is gone. */
    suspend fun fetchMessage(uid: Long): String? {
        val r = command("UID FETCH $uid (UID BODY.PEEK[])")
        if (!r.ok) throw MailException("iCloud Mail couldn't open that email.")
        for (u in r.untagged) {
            val lit = Regex("""BODY\[\]\s*\u0001(\d+)\u0001""").find(u.text)?.groupValues?.get(1)?.toInt() ?: continue
            return u.literals.getOrNull(lit)
        }
        return null
    }

    class FolderInfo(val name: String, val attributes: Set<String>)

    /** Every folder with its attributes (`\Sent`, `\Trash`…). */
    suspend fun list(): List<FolderInfo> {
        val r = command("LIST \"\" \"*\"")
        if (!r.ok) throw MailException("iCloud Mail couldn't list its folders.")
        return r.untagged.mapNotNull { u ->
            val m = Regex("""^\* LIST \(([^)]*)\) (?:"[^"]*"|NIL) (?:"((?:[^"\\]|\\.)*)"|(\S+)|\u0001(\d+)\u0001)""").find(u.text) ?: return@mapNotNull null
            val name = m.groups[2]?.value?.replace(Regex("""\\(.)"""), "$1") ?: m.groups[3]?.value ?: m.groups[4]?.value?.toInt()?.let { u.literals.getOrNull(it) } ?: return@mapNotNull null
            FolderInfo(name, m.groupValues[1].split(' ').filter { it.isNotEmpty() }.toSet())
        }
    }

    /** `APPEND` [message] (a raw string) to [folder] with [flags]; waits for the server's `+` before sending the literal. */
    suspend fun append(folder: String, flags: String, message: String) {
        val bytes = Mime.bytesOf(message)
        val tag = nextTag()
        t.write("$tag APPEND ${quote(folder)} ($flags) {${bytes.size}}\r\n".encodeToByteArray())
        val line = t.readLine() ?: throw MailException("iCloud Mail closed the connection.")
        if (!line.startsWith("+")) throw MailException("iCloud Mail wouldn't take that email.")
        t.write(bytes)
        t.write("\r\n".encodeToByteArray())
        val r = readReply(tag)
        if (!r.ok) throw MailException("iCloud Mail wouldn't save that email.")
    }

    /** Says goodbye; the answer is ignored. */
    suspend fun logout() {
        runCatching { command("LOGOUT") }
        t.close()
    }

    // ---- Inside ----

    private fun rowOf(u: Untagged): ImapHeaderRow? {
        if (!Regex("""^\* \d+ FETCH""").containsMatchIn(u.text)) return null
        val uid = Regex("""\bUID (\d+)""").find(u.text)?.groupValues?.get(1)?.toLong() ?: return null
        val flags = Regex("""FLAGS \(([^)]*)\)""").find(u.text)?.groupValues?.get(1)?.split(' ')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        val date = Regex("""INTERNALDATE "([^"]+)"""").find(u.text)?.groupValues?.get(1)
        val lit = Regex("""BODY\[HEADER\.FIELDS[^\]]*\]\s*\u0001(\d+)\u0001""").find(u.text)?.groupValues?.get(1)?.toInt()
        return ImapHeaderRow(uid, flags, date, lit?.let { u.literals.getOrNull(it) }.orEmpty())
    }

    private fun nextTag() = "A${++tagCounter}"

    private suspend fun command(cmd: String): Reply {
        val tag = nextTag()
        t.write("$tag $cmd\r\n".encodeToByteArray())
        return readReply(tag)
    }

    private suspend fun readReply(tag: String): Reply {
        val untagged = ArrayList<Untagged>()
        while (true) {
            val line = t.readLine() ?: throw MailException("iCloud Mail closed the connection.")
            if (line.startsWith("$tag ")) {
                val rest = line.removePrefix("$tag ")
                return Reply(untagged, rest.startsWith("OK"), rest)
            }
            if (!line.startsWith("*")) continue   // a continuation request or noise: not part of what is read here
            var text = line
            val literals = ArrayList<String>()
            while (true) {
                val m = Regex("""\{(\d+)\}$""").find(text) ?: break
                val size = m.groupValues[1].toInt()
                literals += Mime.rawOf(t.readBytes(size))
                val tail = t.readLine() ?: ""
                text = text.substring(0, m.range.first) + "\u0001${literals.size - 1}\u0001" + tail
            }
            untagged += Untagged(text, literals)
        }
    }

    companion object {
        const val HEADER_FIELDS = "FROM TO CC SUBJECT DATE MESSAGE-ID"

        /** An IMAP quoted string: `"` and `\` escaped; line breaks removed (they would end the command). */
        fun quote(s: String): String = "\"" + s.replace("\r", "").replace("\n", "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

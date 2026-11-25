package com.edith.app.icloud.mail

import com.edith.app.accounts.sources.OutgoingMail
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random
import kotlin.time.Instant

/**
 * Sending over SMTP (RFC 5321) with STARTTLS and `AUTH PLAIN` (K4.10), over a [MailTransport]: iCloud's `smtp.mail.me.com:587`. The password is only
 * sent after TLS is up. Nothing is logged.
 */
class SmtpClient(private val t: MailTransport) {
    private class Reply(val code: Int, val lines: List<String>)

    /** Sends [message] (the full RFC 5322 text, from [composeMessage]) from [from] to [recipients], logging in as [user]. */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun send(user: String, password: String, from: String, recipients: List<String>, message: String) {
        expect(read(), 220, "iCloud Mail didn't greet Edith.")
        var hello = command("EHLO edith.invalid")
        expect(hello, 250, "iCloud Mail didn't accept the connection.")
        expect(command("STARTTLS"), 220, "iCloud Mail wouldn't start a secure connection, so Edith didn't send the password.")
        t.startTls()
        hello = command("EHLO edith.invalid")
        expect(hello, 250, "iCloud Mail didn't accept the secure connection.")
        val auth = command("AUTH PLAIN " + Base64.encode("\u0000$user\u0000$password".encodeToByteArray()))
        if (auth.code == 535 || auth.code == 534) throw MailException("The password was refused.", passwordRejected = true)
        expect(auth, 235, "iCloud Mail didn't accept the login.")
        expect(command("MAIL FROM:<${address(from)}>"), 250, "iCloud Mail didn't accept the sender.")
        for (r in recipients) expect(command("RCPT TO:<${address(r)}>"), 250, "iCloud Mail didn't accept the recipient $r.")
        expect(command("DATA"), 354, "iCloud Mail wouldn't take the message.")
        t.write(Mime.bytesOf(dotStuffed(message)))
        t.write("\r\n.\r\n".encodeToByteArray())
        expect(read(), 250, "iCloud Mail didn't accept the message.")
        runCatching { command("QUIT") }
        t.close()
    }

    private suspend fun command(line: String): Reply {
        t.write("$line\r\n".encodeToByteArray())
        return read()
    }

    private suspend fun read(): Reply {
        val lines = ArrayList<String>()
        while (true) {
            val line = t.readLine() ?: throw MailException("iCloud Mail closed the connection.")
            lines += line
            // "250-more" continues; "250 last" ends (RFC 5321 §4.2.1).
            if (line.length < 4 || line[3] != '-') break
        }
        return Reply(lines.last().take(3).toIntOrNull() ?: 0, lines)
    }

    private fun expect(r: Reply, code: Int, why: String) {
        if (r.code != code) throw MailException(why)
    }

    /** The address with nothing that could end the command (`<`, `>`, line breaks, spaces). */
    private fun address(a: String): String = a.trim().removePrefix("<").removeSuffix(">").filter { it != '\r' && it != '\n' && it != ' ' && it != '<' && it != '>' }

    companion object {
        /** Lines starting with a dot get another (RFC 5321 §4.5.2), and every line ends CRLF. */
        fun dotStuffed(message: String): String =
            message.replace("\r\n", "\n").replace('\r', '\n').split('\n').joinToString("\r\n") { if (it.startsWith(".")) ".$it" else it }.trimEnd('\r', '\n')

        /**
         * The email as RFC 5322 text: headers with no line breaks in them (no header injection), a UTF-8 subject as an RFC 2047 word, and the body as
         * base64 so any text travels intact. [fromAddress] is the iCloud address; [messageIdHost] the part after `@` of the generated `Message-ID`.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun composeMessage(fromAddress: String, m: OutgoingMail, now: Instant, random: Random = Random.Default, messageIdHost: String = "edith.invalid"): String {
            fun clean(s: String) = s.replace(Regex("[\r\n]+"), " ").trim()
            fun word(s: String): String =
                if (s.all { it.code in 32..126 }) s else "=?UTF-8?B?" + Base64.encode(s.encodeToByteArray()) + "?="
            val body = Base64.Mime.encode(m.body.replace("\r\n", "\n").replace("\n", "\r\n").encodeToByteArray())
            return buildString {
                append("From: ").append(clean(fromAddress)).append("\r\n")
                append("To: ").append(clean(m.to)).append("\r\n")
                append("Subject: ").append(word(clean(m.subject))).append("\r\n")
                append("Date: ").append(rfc2822(now)).append("\r\n")
                append("Message-ID: <").append(random.nextLong().toULong().toString(16)).append('.').append(random.nextLong().toULong().toString(16)).append('@').append(messageIdHost).append(">\r\n")
                m.inReplyTo?.let(::clean)?.takeIf { it.isNotEmpty() }?.let { append("In-Reply-To: ").append(it).append("\r\nReferences: ").append(it).append("\r\n") }
                append("MIME-Version: 1.0\r\n")
                append("Content-Type: text/plain; charset=UTF-8\r\n")
                append("Content-Transfer-Encoding: base64\r\n")
                append("\r\n")
                append(body)
            }
        }

        /** `Wed, 25 Nov 2026 14:03:22 +0000`. */
        fun rfc2822(at: Instant): String {
            val d = at.toLocalDateTime(TimeZone.UTC)
            val day = when (d.dayOfWeek) {
                DayOfWeek.MONDAY -> "Mon"; DayOfWeek.TUESDAY -> "Tue"; DayOfWeek.WEDNESDAY -> "Wed"; DayOfWeek.THURSDAY -> "Thu"
                DayOfWeek.FRIDAY -> "Fri"; DayOfWeek.SATURDAY -> "Sat"; else -> "Sun"
            }
            val month = when (d.month) {
                Month.JANUARY -> "Jan"; Month.FEBRUARY -> "Feb"; Month.MARCH -> "Mar"; Month.APRIL -> "Apr"; Month.MAY -> "May"; Month.JUNE -> "Jun"
                Month.JULY -> "Jul"; Month.AUGUST -> "Aug"; Month.SEPTEMBER -> "Sep"; Month.OCTOBER -> "Oct"; Month.NOVEMBER -> "Nov"; else -> "Dec"
            }
            fun two(n: Int) = n.toString().padStart(2, '0')
            return "$day, ${d.day} $month ${d.year} ${two(d.hour)}:${two(d.minute)}:${two(d.second)} +0000"
        }
    }
}

package com.edith.app.icloud.mail

import com.edith.app.accounts.Account
import com.edith.app.accounts.Provider
import com.edith.app.accounts.sources.MailQuery
import com.edith.app.accounts.sources.OutgoingMail
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertFalse
import com.edith.app.testing.assertNotNull
import com.edith.app.testing.assertTrue
import com.edith.app.testing.testDispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.time.Instant

/** K4.10: iCloud mail on the iPhone, against pretend IMAP and SMTP servers speaking the real protocol. */
@OptIn(ExperimentalEncodingApi::class)
class ImapMailSourceTest {
    private val account = Account("i1", Provider.ICloud, "owner@icloud.com", "icloud")
    private val now = Instant.parse("2026-11-25T12:00:00Z")
    private var rejected = 0

    private fun literal(text: String) = "{${text.length}}\r\n$text"

    private fun header(from: String, subject: String, id: String) = "From: $from\r\nTo: owner@icloud.com\r\nSubject: $subject\r\nMessage-ID: <$id>\r\n\r\n"

    private fun fetchRow(index: Int, uid: Int, flags: String, date: String, header: String) =
        "* $index FETCH (UID $uid FLAGS ($flags) INTERNALDATE \"$date\" BODY[HEADER.FIELDS (FROM TO CC SUBJECT DATE MESSAGE-ID)] ${literal(header)})\r\n"

    /** A server with three messages, the newest unread. */
    private fun imap(loginOk: Boolean = true, validity: Int = 99, body: String? = null): FakeMailServer = FakeMailServer("* OK IMAP ready\r\n") { w ->
        val tag = w.substringBefore(' ')
        when {
            w.contains(" LOGIN ") -> if (loginOk) "$tag OK LOGIN completed\r\n" else "$tag NO [AUTHENTICATIONFAILED] Invalid credentials\r\n"
            w.contains(" EXAMINE ") -> "* 3 EXISTS\r\n* OK [UIDVALIDITY $validity] ok\r\n$tag OK [READ-ONLY] done\r\n"
            w.contains(" UID SEARCH ") -> "* SEARCH 10 11 12\r\n$tag OK done\r\n"
            w.contains("UID FETCH 10,11,12 ") ->
                fetchRow(1, 10, "\\Seen", "20-Nov-2026 10:00:00 +0000", header("old@example.com", "Old news", "a@x")) +
                    fetchRow(2, 11, "\\Seen", "24-Nov-2026 09:30:00 +0000", header("=?UTF-8?Q?Jos=C3=A9?= <jose@example.com>", "=?UTF-8?Q?Caf=C3=A9?= plans", "b@x")) +
                    fetchRow(3, 12, "", "25-Nov-2026 08:15:00 +0000", header("Sam <sam@example.com>", "Newest", "c@x")) + "$tag OK done\r\n"
            w.contains("UID FETCH 11 (UID FLAGS") -> fetchRow(1, 11, "\\Seen", "24-Nov-2026 09:30:00 +0000", header("Jose <jose@example.com>", "Cafe plans", "b@x")) + "$tag OK done\r\n"
            w.contains("UID FETCH 11 (UID BODY.PEEK[])") -> "* 2 FETCH (UID 11 BODY[] ${literal(body ?: "Subject: x\r\nContent-Type: text/plain\r\n\r\nSee you at noon.")})\r\n$tag OK done\r\n"
            w.contains(" LOGOUT") -> "* BYE\r\n$tag OK bye\r\n"
            else -> "$tag BAD unexpected\r\n"
        }
    }

    private fun source(server: FakeMailServer = imap(), factory: FakeMailFactory = FakeMailFactory(mapOf(993 to server))) =
        ImapMailSource({ account }, { "pw" }, factory, testDispatchers(), zone = { TimeZone.UTC }, now = { now }, onPasswordRejected = { rejected++ })

    @Test fun searchListsTheNewestFirstAndReadsHeadersWithoutTouchingFlags() = runTest {
        val server = imap()
        val r = (source(server).search(MailQuery(), 10) as Outcome.Success).value
        assertEquals(listOf("Newest", "Café plans", "Old news"), r.messages.map { it.subject })
        assertEquals(listOf("99:12", "99:11", "99:10"), r.messages.map { it.id })
        assertEquals(listOf(true, false, false), r.messages.map { it.unread })
        assertEquals("José <jose@example.com>", r.messages[1].from)
        assertEquals("c@x".let { "<$it>" }, r.messages[0].messageIdHeader)
        assertEquals(Instant.parse("2026-11-25T08:15:00Z"), r.messages[0].date)
        assertTrue("read-only: EXAMINE, never SELECT", server.written.any { it.contains(" EXAMINE ") } && server.written.none { it.contains(" SELECT ") })
        assertTrue("BODY.PEEK so nothing is marked read", server.written.any { it.contains("BODY.PEEK[HEADER.FIELDS") })
        assertTrue("nothing changes a flag", server.written.none { it.contains(" STORE ") || it.contains(" EXPUNGE") })
        assertTrue("the connection was let go", server.closed)
    }

    @Test fun searchWordsGoToTheServerQuotedAndTheDateRangeIsCheckedExactly() = runTest {
        val server = imap()
        val q = MailQuery(from = "sam \"the man\"", unreadOnly = true, after = Instant.parse("2026-11-25T00:00:00Z"), before = Instant.parse("2026-11-26T00:00:00Z"))
        source(server).search(q, 5)
        val search = server.written.first { it.contains("UID SEARCH") }
        assertTrue(search, search.contains("FROM \"sam \\\"the man\\\"\""))
        assertTrue(search.contains("UNSEEN"))
        assertTrue(search.contains("SINCE 25-Nov-2026"))
        assertTrue(search.contains("BEFORE 27-Nov-2026"))   // the day after, so the day the instant falls in is searched
        val r = (source().search(MailQuery(after = Instant.parse("2026-11-25T00:00:00Z")), 5) as Outcome.Success).value
        assertEquals(listOf("Newest"), r.messages.map { it.subject })
    }

    @Test fun readGivesTheBodyAndCc() = runTest {
        val raw = "Subject: x\r\nCc: =?UTF-8?Q?Zo=C3=AB?= <zoe@example.com>\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\nSee you at noon =E2=98=95"
        val m = (source(imap(body = raw)).read("99:11") as Outcome.Success).value
        assertEquals("See you at noon ☕", m.body.trim())
        assertEquals("Zoë <zoe@example.com>", m.cc)
        assertEquals("Café plans".let { "Cafe plans" }, m.summary.subject)
    }

    @Test fun aChangedMailboxAsksForANewList() = runTest {
        val r = source(imap(validity = 100)).read("99:11")
        assertTrue(r is Outcome.Failure)
        assertTrue((r as Outcome.Failure).error.toString().contains("list the emails again") || (r.error as? AppError.ToolFailed)?.detail?.contains("list the emails again") == true)
    }

    @Test fun aRefusedPasswordIsReportedAndRemembered() = runTest {
        val r = source(imap(loginOk = false)).search(MailQuery(), 5)
        assertTrue(r is Outcome.Failure && (r as Outcome.Failure).error is AppError.GoogleAuth)
        assertEquals(1, rejected)
    }

    @Test fun noConnectionIsSaidInWords() = runTest {
        val r = source(factory = FakeMailFactory(emptyMap())).search(MailQuery(), 5)
        assertTrue(r is Outcome.Failure)
        assertTrue(((r as Outcome.Failure).error as AppError.ToolFailed).detail.contains("Couldn't reach iCloud Mail"))
    }

    // ---- Sending ----

    private fun smtp(): FakeMailServer = FakeMailServer("220 smtp ready\r\n") { w ->
        when {
            w.startsWith("EHLO") -> "250-smtp.mail.me.com\r\n250-STARTTLS\r\n250 AUTH PLAIN\r\n"
            w == "STARTTLS\r\n" -> "220 Ready to start TLS\r\n"
            w.startsWith("AUTH PLAIN") -> "235 Authentication successful\r\n"
            w.startsWith("MAIL FROM") || w.startsWith("RCPT TO") -> "250 OK\r\n"
            w == "DATA\r\n" -> "354 Go ahead\r\n"
            w == "\r\n.\r\n" -> "250 Queued\r\n"
            w == "QUIT\r\n" -> "221 Bye\r\n"
            else -> null   // the message itself
        }
    }

    @Test fun sendUsesStartTlsBeforeThePasswordAndKeepsTheMessageIntact() = runTest {
        val server = smtp()
        val m = OutgoingMail("Sam <sam@example.com>, y@z.org", "Café plans\r\nBcc: evil@example.com", "Hi Sam.\n.Leading dot line\nBye ☕", inReplyTo = "<orig@x>")
        val r = source(factory = FakeMailFactory(mapOf(587 to server))).send(m)
        assertTrue(r.toString(), r is Outcome.Success)
        val authAt = server.written.indexOfFirst { it.startsWith("AUTH PLAIN") }
        assertTrue("the password is sent only after TLS is up", authAt > server.tlsStartedAfterWrites!!)
        assertEquals("\u0000owner@icloud.com\u0000pw", Base64.decode(server.written[authAt].removePrefix("AUTH PLAIN ").trim()).decodeToString())
        assertEquals(listOf("RCPT TO:<sam@example.com>\r\n", "RCPT TO:<y@z.org>\r\n"), server.written.filter { it.startsWith("RCPT") })
        val data = server.written[server.written.indexOf("DATA\r\n") + 1]
        assertTrue("the subject is one header line, with the break turned into a space", data.contains("Subject: ") && !data.contains("\r\nBcc:"))
        assertTrue("a non-ASCII subject is an encoded word", data.contains("Subject: =?UTF-8?B?"))
        assertTrue(data.contains("In-Reply-To: <orig@x>"))
        val body = Mime.parse(data)
        assertEquals("Hi Sam.\r\n.Leading dot line\r\nBye ☕", Mime.text(Mime.parse(data.replace("\r\n..", "\r\n."))).trim())
        assertNotNull(body.header("Message-ID"))
        assertTrue(server.closed)
    }

    @Test fun smtpDotStuffingDoublesALeadingDot() {
        assertEquals("a\r\n..b\r\nc", SmtpClient.dotStuffed("a\n.b\r\nc\r\n"))
    }

    @Test fun aRefusedSmtpPasswordIsAPasswordProblem() = runTest {
        val server = FakeMailServer("220 ready\r\n") { w ->
            when {
                w.startsWith("EHLO") -> "250 ok\r\n"
                w == "STARTTLS\r\n" -> "220 go\r\n"
                w.startsWith("AUTH") -> "535 5.7.8 Authentication failed\r\n"
                else -> null
            }
        }
        val r = source(factory = FakeMailFactory(mapOf(587 to server))).send(OutgoingMail("a@b.com", "s", "b"))
        assertTrue(r is Outcome.Failure && (r as Outcome.Failure).error is AppError.GoogleAuth)
        assertEquals(1, rejected)
    }

    @Test fun anEmailWithNoAddressIsNotSent() = runTest {
        val r = source(factory = FakeMailFactory(mapOf(587 to smtp()))).send(OutgoingMail("not an address", "s", "b"))
        assertTrue(r is Outcome.Failure)
    }

    // ---- Helpers ----

    @Test fun datesAndAddressesParse() {
        assertEquals(Instant.parse("2026-11-25T13:03:22Z"), ImapMailSource.parseInternalDate("25-Nov-2026 14:03:22 +0100"))
        assertEquals(Instant.parse("2026-11-25T14:03:22Z"), ImapMailSource.parseRfc2822Date("Wed, 25 Nov 2026 14:03:22 +0000"))
        assertEquals("Wed, 25 Nov 2026 12:00:00 +0000", SmtpClient.rfc2822(Instant.parse("2026-11-25T12:00:00Z")))
        assertEquals(listOf("sam@example.com", "y@z.org"), ImapMailSource.addresses("Sam <sam@example.com>; y@z.org"))
        assertEquals(99L to 11L, ImapMailSource.parseId("99:11"))
        assertFalse(ImapMailSource.parseId("nope") != null)
    }
}

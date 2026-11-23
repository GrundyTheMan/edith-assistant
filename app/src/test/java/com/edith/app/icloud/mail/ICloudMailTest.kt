package com.edith.app.icloud.mail

import com.edith.app.accounts.Account
import kotlinx.datetime.TimeZone
import com.edith.app.accounts.Provider
import com.edith.app.accounts.sources.MailQuery
import com.edith.app.accounts.sources.OutgoingMail
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.testing.AppDispatchersForTests
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.icegreen.greenmail.util.ServerSetupTest
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Date
import java.util.Properties

/**
 * C24 §2: iCloud mail over IMAP and SMTP against GreenMail (a fake mail server, tests only). The mailbox holds made-up
 * mail. Edith must never mark mail as read (trap 2).
 */
class ICloudMailTest {
    private val zone = TimeZone.of("America/Detroit")
    private val mail = GreenMail(ServerSetup.dynamicPort(ServerSetupTest.SMTP_IMAP))
    private val account = Account("i1", Provider.ICloud, "owner@icloud.com", "icloud", login = "owner")
    private var rejected = 0

    @Before fun setUp() {
        mail.start()
        mail.setUser("owner@icloud.com", "owner", "abcd-efgh-ijkl-mnop")
        withStore { store ->
            val inbox = store.getFolder("INBOX").apply { open(Folder.READ_WRITE) }
            inbox.appendMessages(
                arrayOf(
                    message("Boss <boss@contoso.com>", "Budget", "2026-11-12T15:00:00Z", "Can you send it?\n\nOn Tue, Nov 10, Owner wrote:\n> old text"),
                    message("Mom <mom@example.com>", "Dinner", "2026-11-12T03:00:00Z", "Sunday?"),
                    message("Boss <boss@contoso.com>", "Old budget", "2026-11-01T15:00:00Z", "Done.").apply { setFlag(Flags.Flag.SEEN, true) },
                ),
            )
            inbox.close(false)
            store.getFolder("Drafts").create(Folder.HOLDS_MESSAGES)
        }
    }

    @After fun tearDown() = mail.stop()

    private fun servers() = MailServers(
        imapHost = "127.0.0.1", imapPort = mail.imap.port, imapSsl = false,
        smtpHost = "127.0.0.1", smtpPort = mail.smtp.port, smtpStartTls = false, timeoutMs = 5_000,
    )

    private fun source(password: String = "abcd-efgh-ijkl-mnop", appendToSent: Boolean = false) =
        ICloudMailSource(
            { account }, { password }, AppDispatchersForTests.unconfined, servers(), { zone }, appendToSent, { rejected++ },
            // GreenMail doesn't report SPECIAL-USE flags; finding by flag is tested in SpecialUseFolderTest.
            findFolder = { store, flag -> if (flag == "\\Drafts") store.getFolder("Drafts") else null },
        )

    private fun message(from: String, subject: String, at: String, body: String) = object : MimeMessage(Session.getInstance(Properties())) {
        override fun getReceivedDate(): Date = Date.from(Instant.parse(at))   // becomes the INTERNALDATE on APPEND
    }.apply {
        setFrom(InternetAddress(from))
        setRecipients(Message.RecipientType.TO, "owner@icloud.com")
        setSubject(subject)
        sentDate = Date.from(Instant.parse(at))
        setText(body, "UTF-8")
        saveChanges()
    }

    private fun <T> withStore(block: (Store) -> T): T =
        Session.getInstance(Properties()).getStore("imap").use { it.connect("127.0.0.1", mail.imap.port, "owner", "abcd-efgh-ijkl-mnop"); block(it) }

    /** Each inbox message's subject and whether it's \Seen, read without marking anything. */
    private fun seen(): Map<String, Boolean> = withStore { store ->
        val inbox = store.getFolder("INBOX").apply { open(Folder.READ_ONLY) }
        inbox.messages.associate { it.subject to it.isSet(Flags.Flag.SEEN) }.also { inbox.close(false) }
    }

    private val midnightNov12 = kotlin.time.Instant.parse("2026-11-12T05:00:00Z")

    @Test fun `unread today - SEARCH narrows by day, the exact local midnight is checked here`() = runBlocking {
        val r = (source().search(MailQuery(unreadOnly = true, after = midnightNov12), 10) as Outcome.Success).value
        assertEquals("Mom's came at 10 PM on the 11th, local time", listOf("Budget"), r.messages.map { it.subject })
        val m = r.messages.single()
        assertEquals("Boss <boss@contoso.com>", m.from)
        assertTrue(m.unread)
        assertEquals("i1", m.accountId)
        assertTrue(m.id, m.id.matches(Regex("\\d+:\\d+")))
        assertTrue(m.messageIdHeader!!.startsWith("<"))
        assertFalse(r.more)
    }

    @Test fun `from, newest first, and more when there are more`() = runBlocking {
        val r = (source().search(MailQuery(from = "boss@contoso.com"), 1) as Outcome.Success).value
        assertEquals(listOf("Budget"), r.messages.map { it.subject })
        assertTrue(r.more)
        val all = (source().search(MailQuery(), 10) as Outcome.Success).value
        assertEquals(listOf("Budget", "Dinner", "Old budget"), all.messages.map { it.subject })
        val text = (source().search(MailQuery(text = "Sunday"), 10) as Outcome.Success).value
        assertEquals(listOf("Dinner"), text.messages.map { it.subject })
    }

    @Test fun `searching and reading never mark mail as read`() = runBlocking {
        val before = seen()
        val found = (source().search(MailQuery(unreadOnly = true), 10) as Outcome.Success).value
        found.messages.forEach { source().read(it.id) }
        assertEquals(mapOf("Budget" to false, "Dinner" to false, "Old budget" to true), before)
        assertEquals("trap 2", before, seen())
    }

    @Test fun `read - the text with the quoted history removed, and a stale id says list again`() = runBlocking {
        val budget = (source().search(MailQuery(subject = "Budget", unreadOnly = true), 10) as Outcome.Success).value.messages.single()
        val m = (source().read(budget.id) as Outcome.Success).value
        assertEquals("Can you send it?", m.body)
        assertEquals("Budget", m.summary.subject)
        val (_, uid) = ICloudMailSource.parseId(budget.id)!!
        assertEquals(Outcome.Failure(AppError.ToolFailed("email", ICloudMailSource.LIST_AGAIN)), source().read("1:$uid"))
    }

    @Test fun `send - SMTP with the full address, reply headers, a UTF-8 subject and no header injection`() = runBlocking {
        mail.setUser("smtp-login@icloud.com", "owner@icloud.com", "abcd-efgh-ijkl-mnop")   // iCloud's SMTP login is the full address
        val r = source().send(OutgoingMail("Boss <boss@contoso.com>", "Re: Budget café\r\nBcc: evil@example.com", "Sent.\nSam", inReplyTo = "<orig@contoso.com>"))
        assertEquals(Outcome.Success(Unit), r)
        val got = mail.receivedMessages.single { it.getHeader("In-Reply-To") != null }
        assertEquals("Re: Budget café Bcc: evil@example.com", got.subject)
        assertTrue(got.getHeader("Subject")!!.single().startsWith("=?UTF-8?"))
        assertEquals(listOf("boss@contoso.com"), got.allRecipients.map { (it as InternetAddress).address })
        assertEquals("owner@icloud.com", (got.from.single() as InternetAddress).address)
        assertEquals("<orig@contoso.com>", got.getHeader("In-Reply-To")!!.single())
        assertEquals("<orig@contoso.com>", got.getHeader("References")!!.single())
        assertEquals("Sent.\r\nSam", (got.content as String).trim())
    }

    @Test fun `a draft is appended to the Drafts folder, marked as a draft, and nothing is sent`() = runBlocking {
        val r = source().draft(OutgoingMail("boss@contoso.com", "Re: Budget", "Thanks!", inReplyTo = "<orig@contoso.com>"))
        assertEquals(Outcome.Success(Unit), r)
        assertTrue("no SMTP", mail.receivedMessages.none { it.getHeader("In-Reply-To") != null && it.folder?.fullName == "INBOX" })
        withStore { store ->
            val drafts = store.getFolder("Drafts").apply { open(Folder.READ_ONLY) }
            val d = drafts.messages.single()
            assertEquals("Re: Budget", d.subject)
            assertTrue(d.isSet(Flags.Flag.DRAFT))
            assertEquals("<orig@contoso.com>", d.getHeader("In-Reply-To").single())
            drafts.close(false)
        }
    }

    @Test fun `a refused password tells the account, with no retry`() = runBlocking {
        val r = source(password = "wrong").search(MailQuery(), 5)
        assertEquals(Outcome.Failure(AppError.GoogleAuth(ICloudMailSource.PASSWORD_STOPPED)), r)
        assertEquals(1, rejected)
    }

    @Test fun `login tries what it's given, and the add-account check uses it`() {
        source().login("owner", "abcd-efgh-ijkl-mnop")
        try {
            source().login("owner", "wrong")
            error("should have been refused")
        } catch (e: jakarta.mail.AuthenticationFailedException) {
            // expected
        }
    }
}

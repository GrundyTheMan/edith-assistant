package com.edith.app.icloud.mail

import jakarta.mail.Session
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Properties
import kotlin.concurrent.thread

/**
 * C24 trap 3: Drafts and Sent are found by their SPECIAL-USE flags (RFC 6154), never by name. A scripted IMAP server
 * answers LIST with localised names (as a German mailbox would, in modified UTF-7), so only the flags can find them.
 */
class SpecialUseFolderTest {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

    @After fun tearDown() = server.close()

    /** The LIST answer; the default is a mailbox flagging both, with localised names and a decoy. */
    private var listLines = listOf(
        """* LIST (\HasNoChildren) "/" "INBOX"""",
        """* LIST (\HasNoChildren \Drafts) "/" "Entw&APw-rfe"""",
        """* LIST (\HasNoChildren \Sent) "/" "Gesendete Objekte"""",
        """* LIST (\HasNoChildren) "/" "Drafts"""",   // a decoy with the English name
    )

    private fun serve() = thread(isDaemon = true) {
        server.accept().use { s ->
            val input = s.getInputStream().bufferedReader()
            val out = s.getOutputStream().bufferedWriter()
            fun send(line: String) { out.write(line + "\r\n"); out.flush() }
            send("* OK [CAPABILITY IMAP4rev1 SPECIAL-USE AUTH=PLAIN] ready")
            while (true) {
                val line = input.readLine() ?: break
                val tag = line.substringBefore(' ')
                val command = line.substringAfter(' ').substringBefore(' ').uppercase()
                when (command) {
                    "CAPABILITY" -> { send("* CAPABILITY IMAP4rev1 SPECIAL-USE AUTH=PLAIN"); send("$tag OK done") }
                    "LOGIN" -> send("$tag OK [CAPABILITY IMAP4rev1 SPECIAL-USE] logged in")
                    "LIST" -> {
                        listLines.forEach(::send)
                        send("$tag OK done")
                    }
                    "LOGOUT" -> { send("* BYE"); send("$tag OK bye"); break }
                    else -> send("$tag OK")
                }
            }
        }
    }

    @Test fun `Drafts and Sent are found by flag, not by name`() {
        serve()
        Session.getInstance(Properties()).getStore("imap").use { store ->
            store.connect("127.0.0.1", server.localPort, "owner", "x")
            assertEquals("Entwürfe", ICloudMailSource.specialFolder(store, "\\Drafts")?.fullName)
            assertEquals("Gesendete Objekte", ICloudMailSource.specialFolder(store, "\\Sent")?.fullName)
            assertNull(ICloudMailSource.specialFolder(store, "\\Junk"))
        }
    }

    @Test fun `iCloud flags only Sent and Trash, so Drafts falls back to iCloud's own server name`() {
        // What iCloud's IMAP server answered on 2026-11-13 (no SPECIAL-USE capability).
        listLines = listOf(
            """* LIST (\HasNoChildren) "/" "Archive"""",
            """* LIST (\HasNoChildren) "/" "Junk"""",
            """* LIST (\Noinferiors) "/" "INBOX"""",
            """* LIST (\HasNoChildren \Trash) "/" "Deleted Messages"""",
            """* LIST (\HasNoChildren \Sent) "/" "Sent Messages"""",
            """* LIST (\HasNoChildren) "/" "Drafts"""",
        )
        serve()
        Session.getInstance(Properties()).getStore("imap").use { store ->
            store.connect("127.0.0.1", server.localPort, "owner", "x")
            assertEquals("Drafts", ICloudMailSource.specialFolder(store, "\\Drafts")?.fullName)
            assertEquals("by its flag", "Sent Messages", ICloudMailSource.specialFolder(store, "\\Sent")?.fullName)
        }
    }
}

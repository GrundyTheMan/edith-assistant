package com.edith.app.google.gmail

import com.edith.app.accounts.sources.OutgoingMail
import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertFalse
import com.edith.app.testing.assertTrue
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test

/** C19 §1 (K2.6b): Gmail's MIME text and outgoing message, now run on the desktop and the iPhone simulator too (the Base64 is Kotlin's own). */
@OptIn(ExperimentalEncodingApi::class)
class MailTextTest {
    private val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    private fun b64(text: String) = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(text.encodeToByteArray())

    private fun part(mime: String, text: String? = null, parts: List<PartDto> = emptyList(), filename: String? = null) =
        PartDto(mimeType = mime, filename = filename, body = text?.let { BodyDto(b64(it)) }, parts = parts)

    @Test fun plainTextIsPreferredHtmlIsMadeReadableAttachmentsAreSkipped() {
        assertEquals("Hello\nSee you Friday.", MailText.body(part("multipart/alternative", parts = listOf(part("text/plain", "Hello\nSee you Friday."), part("text/html", "<p>Hello</p>")))))
        assertTrue(MailText.body(part("text/html", "<html><body><p>Field trip <b>Friday</b>.</p><p>Bring lunch.</p></body></html>")).let { it.contains("Field trip Friday.") && it.contains("Bring lunch.") })
        val mixed = part("multipart/mixed", parts = listOf(
            part("text/plain", "attached notes", filename = "notes.txt"),
            part("multipart/alternative", parts = listOf(part("text/plain", "The real body"))),
        ))
        assertEquals("The real body", MailText.body(mixed))
        assertEquals("base64url with - and _ decoded", "é?>>", MailText.body(part("text/plain", "é?>>")))
    }

    @Test fun aLongBodyIsCutAtTheLimit() {
        val body = MailText.body(part("text/plain", "x".repeat(10_000)))
        assertTrue(body.startsWith("x".repeat(MailText.MAX_BODY)) && body.endsWith("[…the rest of the email is cut]"))
    }

    @Test fun theRawMessageHasTheHeadersAUtf8SubjectInRfc2047AndTheBody() {
        val raw = MailText.raw(OutgoingMail("sam@example.com", "Café on Friday", "Thanks!\nSee you there.", inReplyTo = "<sam-1@example.com>"))
        val text = urlSafe.decode(raw).decodeToString()
        val (head, body) = text.split("\r\n\r\n", limit = 2)
        assertTrue(head.contains("To: sam@example.com\r\n"))
        assertTrue(head.contains("Subject: =?UTF-8?B?" + Base64.encode("Café on Friday".encodeToByteArray()) + "?="))
        assertTrue(head.contains("In-Reply-To: <sam-1@example.com>\r\nReferences: <sam-1@example.com>"))
        assertTrue(head.contains("Content-Type: text/plain; charset=UTF-8"))
        assertEquals("Thanks!\r\nSee you there.", Base64.Mime.decode(body).decodeToString())
        assertEquals("plain ASCII stays", "Lunch", MailText.encodeHeader("Lunch"))
        assertFalse("no header injection", urlSafe.decode(MailText.raw(OutgoingMail("a@b.c\r\nBcc: x@y.z", "s", "b"))).decodeToString().contains("\r\nBcc:"))
    }

    @Test fun aLongBodyIsWrappedAt76CharactersWithCrLf() {
        val raw = MailText.raw(OutgoingMail("sam@example.com", "Long", "word ".repeat(100)))
        val body = urlSafe.decode(raw).decodeToString().split("\r\n\r\n", limit = 2)[1]
        assertTrue(body.lines().all { it.length <= 76 })
        assertEquals("word ".repeat(100).replace("\n", "\r\n"), Base64.Mime.decode(body).decodeToString())
    }
}

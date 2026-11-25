package com.edith.app.icloud.mail

import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertNull
import com.edith.app.testing.assertTrue
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test

/** K4.10: reading an email's text, on every platform. */
@OptIn(ExperimentalEncodingApi::class)
class MimeTest {
    @Test fun headersAreUnfoldedAndWordsDecoded() {
        val e = Mime.parse("Subject: =?UTF-8?B?${Base64.encode("Café ☕".encodeToByteArray())}?=\r\nFrom: =?ISO-8859-1?Q?Jos=E9?= <jose@example.com>\r\nTo: a@x.com,\r\n b@y.com\r\n\r\nbody")
        assertEquals("Café ☕", Mime.decodeWords(e.header("Subject")!!))
        assertEquals("José <jose@example.com>", Mime.decodeWords(e.header("From")!!))
        assertEquals("a@x.com, b@y.com", e.header("To"))
        assertEquals("body", e.body)
    }

    @Test fun adjacentEncodedWordsJoinWithoutTheSpaceBetween() {
        assertEquals("ab", Mime.decodeWords("=?UTF-8?Q?a?= =?UTF-8?Q?b?="))
    }

    @Test fun quotedPrintableBodiesDecodeWithSoftBreaks() {
        val e = Mime.parse("Content-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\nCaf=C3=A9 au la=\r\nit =E2=82=AC5")
        assertEquals("Café au lait €5", Mime.text(e))
    }

    @Test fun base64BodiesDecodeInTheirCharset() {
        val latin = Mime.rawOf(byteArrayOf(0x4a, 0xe9.toByte(), 0x73, 0x75, 0x73))   // "Jésus" in ISO-8859-1
        val e = Mime.parse("Content-Type: text/plain; charset=\"iso-8859-1\"\r\nContent-Transfer-Encoding: base64\r\n\r\n${Base64.Mime.encode(Mime.bytesOf(latin))}")
        assertEquals("Jésus", Mime.text(e))
    }

    @Test fun windows1252KeepsItsCurlyQuotes() {
        assertEquals("“hi”", Mime.decode(byteArrayOf(0x93.toByte(), 'h'.code.toByte(), 'i'.code.toByte(), 0x94.toByte()), "windows-1252"))
    }

    @Test fun anAlternativeMessagePrefersItsPlainPart() {
        val raw = "Content-Type: multipart/alternative; boundary=\"b1\"\r\n\r\n--b1\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nPlain words\r\n--b1\r\nContent-Type: text/html\r\n\r\n<p>Html words</p>\r\n--b1--\r\n"
        assertEquals("Plain words", Mime.readableText(Mime.parse(raw)).trim())
    }

    @Test fun htmlOnlyMessagesAreMadeReadable() {
        val raw = "Content-Type: text/html; charset=utf-8\r\n\r\n<html><body><p>Hello <b>there</b></p></body></html>"
        assertTrue(Mime.readableText(Mime.parse(raw)).contains("Hello there"))
    }

    @Test fun anAttachmentIsNeverTheText() {
        val raw = "Content-Type: multipart/mixed; boundary=\"m\"\r\n\r\n--m\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename=\"notes.txt\"\r\n\r\nSecret attachment\r\n--m\r\nContent-Type: text/plain\r\n\r\nThe message\r\n--m--\r\n"
        assertEquals("The message", Mime.readableText(Mime.parse(raw)).trim())
    }

    @Test fun nestedMultipartIsSearched() {
        val raw = "Content-Type: multipart/mixed; boundary=\"outer\"\r\n\r\n--outer\r\nContent-Type: multipart/alternative; boundary=\"inner\"\r\n\r\n--inner\r\nContent-Type: text/plain\r\n\r\nDeep text\r\n--inner--\r\n--outer--\r\n"
        assertEquals("Deep text", Mime.readableText(Mime.parse(raw)).trim())
    }

    @Test fun aMessageWithNoBodyIsEmptyNotACrash() {
        assertEquals("", Mime.readableText(Mime.parse("Subject: x\r\n\r\n")))
        assertNull(Mime.param(null, "charset"))
    }
}

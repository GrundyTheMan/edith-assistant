package com.edith.app.accounts.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** K2.6a: the provider-neutral email text rules, now run on the desktop and the iPhone simulator too. */
class MailBodyTest {
    @Test fun quotedHistoryIsRemovedWhereItIsObvious() {
        val gmail = "Sounds good!\n\nOn Thu, Oct 29, 2026 at 12:00 PM Sam Lee <sam@example.com> wrote:\n> Are we on for Friday?\n> Sam"
        assertEquals("Sounds good!", MailBody.stripQuoted(gmail).trim())
        assertEquals("Yes.", MailBody.stripQuoted("Yes.\n-----Original Message-----\nFrom: x").trim())
        assertEquals("Line one\nLine two", MailBody.stripQuoted("Line one\n> quoted\nLine two"))
        assertEquals("On Friday I'll bring cake.", MailBody.stripQuoted("On Friday I'll bring cake."))
    }

    @Test fun aLongBodyIsCutAtTheLimit() {
        val body = MailBody.clean("x".repeat(10_000))
        assertTrue(body.startsWith("x".repeat(MailBody.MAX_BODY)) && body.endsWith("[…the rest of the email is cut]"))
    }

    @Test fun windowsLineEndingsAreNormalised() {
        assertEquals("a\nb", MailBody.clean("a\r\nb\r\n"))
    }
}

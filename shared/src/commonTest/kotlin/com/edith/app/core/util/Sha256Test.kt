package com.edith.app.core.util

import com.edith.app.google.browser.BrowserOAuth
import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertFalse
import com.edith.app.testing.assertTrue
import kotlin.test.Test

/** K2.6: the hash and the secure-random seam a browser sign-in needs, checked against the standard vectors on every platform. */
class Sha256Test {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun sha(text: String) = hex(Sha256.digest(text.encodeToByteArray()))

    @Test fun theEmptyMessage() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha(""))
    }

    @Test fun abc() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha("abc"))
    }

    @Test fun aMessageThatNeedsASecondBlockForItsPadding() {
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", sha("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))
    }

    @Test fun aMillionLettersAManyBlocksMessage() {
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", sha("a".repeat(1_000_000)))
    }

    @Test fun thePkceChallengeFromRfc7636() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", BrowserOAuth.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun constantTimeEqualsComparesLengthAndContent() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertTrue(constantTimeEquals(byteArrayOf(), byteArrayOf()))
    }

    @Test fun theSystemRandomGivesDifferentBytesEachTime() {
        val a = ByteArray(32).also { systemRandom().nextBytes(it) }
        val b = ByteArray(32).also { systemRandom().nextBytes(it) }
        assertFalse(a.contentEquals(b))
        assertFalse(a.all { it == 0.toByte() })
    }
}

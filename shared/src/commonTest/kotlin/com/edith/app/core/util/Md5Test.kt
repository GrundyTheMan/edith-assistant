package com.edith.app.core.util

import com.edith.app.testing.assertEquals
import kotlin.test.Test

/** K2.5: MD5 against the test suite in RFC 1321 §A.5, on every platform. */
class Md5Test {
    private fun md5(text: String) = Md5.digest(text.encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test fun theRfcVectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5(""))
        assertEquals("0cc175b9c0f1b6a831c399e269772661", md5("a"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5("abc"))
        assertEquals("f96b697d7cb7938d525a2f31aaf161d0", md5("message digest"))
        assertEquals("c3fcd3d76192e4007dfb496cca67e13b", md5("abcdefghijklmnopqrstuvwxyz"))
        assertEquals("d174ab98d277d9f5a5611c2c9f419d9f", md5("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"))
        assertEquals("57edf4a22be3c955ac49da2e2107b67a", md5("12345678901234567890123456789012345678901234567890123456789012345678901234567890"))
    }
}

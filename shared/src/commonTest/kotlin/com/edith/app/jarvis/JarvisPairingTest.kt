package com.edith.app.jarvis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** K2.4: the pure parts of the Jarvis client run the same on every platform. */
class JarvisPairingTest {
    private fun addr(text: String) = JarvisPairing.address(text)

    @Test fun aBareHostGetsTheDefaultPort() {
        val (url, problem) = addr("192.168.1.20")
        assertNull(problem)
        assertEquals("https://192.168.1.20:47821/", url.toString())
    }

    @Test fun aGivenPortIsKept() = assertEquals("https://192.168.1.20:5000/", addr("192.168.1.20:5000").first.toString())

    @Test fun aFullAddressIsReducedToHostAndPort() = assertEquals("https://pc:47821/", addr(" https://pc:47821/some/path?x=1 ").first.toString())

    @Test fun plainHttpIsRefusedInWords() {
        val (url, problem) = addr("http://192.168.1.20:47821")
        assertNull(url)
        assertEquals(JarvisPairing.CLEARTEXT, problem)
    }

    @Test fun nothingOrNonsenseIsNotAnAddress() {
        assertEquals(JarvisPairing.NOT_AN_ADDRESS, addr("").second)
        assertEquals(JarvisPairing.NOT_AN_ADDRESS, addr("   ").second)
        assertEquals(JarvisPairing.NOT_AN_ADDRESS, addr("https://").second)
    }

    @Test fun theFingerprintIsTheLowerCaseSha256Hex() =
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", JarvisPairing.fingerprint(byteArrayOf(1, 2, 3)))

    @Test fun theShortFingerprintIsSixGroupsOfFour() =
        assertEquals("0390 58c6 f2c0 cb49 2c53 3b0a", JarvisPairing.shortFingerprint("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"))

    @Test fun theProofIgnoresDashesAndCaseInTheCode() {
        val fp = "00".repeat(32)
        val expected = "9810c78839a11eb42e0d60ed0ad9ee6459f48973343ef4d86dd78f13b7fbd7b7"
        assertEquals(expected, JarvisPairing.proof("ABCDE-FGHJK", fp))
        assertEquals(expected, JarvisPairing.proof("abcde-fghjk", fp))
        assertEquals(expected, JarvisPairing.proof("ABCDEFGHJK", fp.uppercase()))
    }

    @Test fun theFeedUrlKeepsEachSegmentWholeAndEncodesTheQuery() {
        val url = JarvisApi.feedUrl("https://192.168.1.20:47821", listOf("printer", "camera", "a/b c", "snapshot"), mapOf("name" to "x y.gcode"))
        assertNotNull(url)
        assertTrue(url.startsWith("https://192.168.1.20:47821/v1/printer/camera/"), url)
        assertTrue("a%2Fb" in url, url)   // a slash inside a segment never becomes a separator
        assertTrue(url.endsWith("/snapshot?name=x+y.gcode") || url.endsWith("/snapshot?name=x%20y.gcode"), url)
    }

    @Test fun aDamagedBaseGivesNoUrl() = assertNull(JarvisApi.feedUrl("not an address", listOf("hello"), emptyMap()))

    @Test fun thePinAcceptsTheRightCertificateAndRemembersWhatItSaw() {
        val der = byteArrayOf(1, 2, 3)
        val pin = PinCheck(JarvisPairing.fingerprint(der))
        assertTrue(pin.accepts(der))
        assertEquals(JarvisPairing.fingerprint(der), pin.seen)
        assertFalse(pin.takeRefusal())
    }

    @Test fun thePinRefusesAnotherCertificateAndSaysSoOnce() {
        val pin = PinCheck(JarvisPairing.fingerprint(byteArrayOf(1, 2, 3)))
        assertFalse(pin.accepts(byteArrayOf(9, 9)))
        assertTrue(pin.takeRefusal())
        assertFalse(pin.takeRefusal())
    }

    @Test fun withNoPinYetNothingIsRefusedButTheCertificateIsRemembered() {
        val pin = PinCheck(null)
        assertTrue(pin.accepts(byteArrayOf(4, 5)))
        assertEquals(JarvisPairing.fingerprint(byteArrayOf(4, 5)), pin.seen)
    }
}

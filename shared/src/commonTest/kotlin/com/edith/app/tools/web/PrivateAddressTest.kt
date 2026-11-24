package com.edith.app.tools.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** K4.2: the private-address rules for what a name resolves to, shared by every platform. */
class PrivateAddressTest {
    private fun v4(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())
    private fun v6(vararg bytes: Int): ByteArray = ByteArray(16).also { out -> bytes.forEachIndexed { i, v -> out[i] = v.toByte() } }

    @Test fun privateV4RangesAreRefused() {
        for (a in listOf(v4(10, 0, 0, 1), v4(127, 0, 0, 1), v4(192, 168, 4, 82), v4(172, 16, 0, 5), v4(169, 254, 1, 1), v4(100, 94, 208, 70), v4(0, 0, 0, 0))) {
            assertTrue(PrivateNetwork.isPrivateAddress(a), a.toList().toString())
        }
    }

    @Test fun publicV4AddressesPass() {
        for (a in listOf(v4(93, 184, 216, 34), v4(8, 8, 8, 8), v4(172, 32, 0, 1), v4(1, 1, 1, 1))) {
            assertFalse(PrivateNetwork.isPrivateAddress(a), a.toList().toString())
        }
    }

    @Test fun ipv6LoopbackUniqueLocalAndLinkLocalAreRefused() {
        assertTrue(PrivateNetwork.isPrivateAddress(v6()))                        // ::
        assertTrue(PrivateNetwork.isPrivateAddress(v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))   // ::1
        assertTrue(PrivateNetwork.isPrivateAddress(v6(0xfd, 0x12)))              // fd12::
        assertTrue(PrivateNetwork.isPrivateAddress(v6(0xfe, 0x80)))              // fe80::
        assertTrue(PrivateNetwork.isPrivateAddress(v6(0xff, 0x02)))              // ff02::
    }

    @Test fun aPublicIpv6AddressPasses() {
        assertFalse(PrivateNetwork.isPrivateAddress(v6(0x26, 0x06, 0x47, 0x00)))   // 2606:4700::
    }

    @Test fun anIpv4AddressInsideIpv6IsJudgedAsIpv4() {
        val mapped = { a: Int, b: Int -> v6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, a, b, 1, 1) }
        assertTrue(PrivateNetwork.isPrivateAddress(mapped(192, 168)))
        assertFalse(PrivateNetwork.isPrivateAddress(mapped(8, 8)))
    }

    @Test fun anUnknownLengthIsRefused() {
        assertTrue(PrivateNetwork.isPrivateAddress(ByteArray(5)))
    }

    @Test fun aNameThatResolvesToAPrivateAddressIsRefusedAndAPublicOneAllowed() {
        val dns = mapOf("evil.example" to listOf(v4(8, 8, 8, 8), v4(192, 168, 1, 10)), "news.example" to listOf(v4(93, 184, 216, 34)))
        assertFalse(publicHostAllowed("evil.example") { dns[it].orEmpty() })
        assertTrue(publicHostAllowed("news.example") { dns[it].orEmpty() })
    }

    @Test fun aLanNameIsRefusedWithoutAnyLookup() {
        var lookups = 0
        assertFalse(publicHostAllowed("printer.local") { lookups++; emptyList() })
        assertEquals(0, lookups)
    }

    @Test fun aNameThatDoesNotResolveIsAllowedSoTheFetchCanSayCouldNotFindIt() {
        assertTrue(publicHostAllowed("no-such-name.example") { emptyList() })
    }
}

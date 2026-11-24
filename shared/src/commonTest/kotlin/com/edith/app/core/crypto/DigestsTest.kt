package com.edith.app.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/** K2.4: SHA-256 and HMAC-SHA256 give the same answers on every platform (the iPhone's come from CommonCrypto). Vectors from RFC 4231 and Node's crypto. */
class DigestsTest {
    @Test fun sha256OfAbc() = assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256("abc".encodeToByteArray()).toLowerHex())

    @Test fun sha256OfNothing() = assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256(ByteArray(0)).toLowerHex())

    @Test fun sha256OfBinaryBytes() = assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", sha256(byteArrayOf(1, 2, 3)).toLowerHex())

    @Test fun hmacRfc4231CaseTwo() = assertEquals(
        "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
        hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()).toLowerHex(),
    )

    @Test fun hmacWithAnEmptyKey() = assertEquals("4cbc96099a6467ce002461f10549b4898265ebe6188b45efacc44293516e62c4", hmacSha256(ByteArray(0), "x".encodeToByteArray()).toLowerHex())

    @Test fun hexIsLowerCaseAndTwoCharactersAByte() = assertEquals("000fa0ff", byteArrayOf(0, 15, -96, -1).toLowerHex())
}

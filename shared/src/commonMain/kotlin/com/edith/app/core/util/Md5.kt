package com.edith.app.core.util

import kotlin.math.abs
import kotlin.math.sin

/**
 * MD5 (RFC 1321) in plain Kotlin, for the one place a protocol demands it: Meross signs its requests with `md5(...)` (K2.5). It is not used for
 * anything that needs to resist an attacker; the SHA-256 next to it is for that. Checked against RFC 1321's test vectors in `Md5Test`.
 */
object Md5 {
    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    /** floor(2^32 x abs(sin(i + 1))), as RFC 1321 defines the constants. */
    private val K = IntArray(64) { i -> (abs(sin(i + 1.0)) * 4294967296.0).toLong().toInt() }

    private fun rotl(x: Int, n: Int) = (x shl n) or (x ushr (32 - n))

    /** The 16-byte MD5 digest of [message]. */
    fun digest(message: ByteArray): ByteArray {
        var a0 = 0x67452301
        var b0 = 0xefcdab89u.toInt()
        var c0 = 0x98badcfeu.toInt()
        var d0 = 0x10325476

        val bitLength = message.size.toLong() * 8
        val paddedSize = ((message.size + 8) / 64 + 1) * 64
        val padded = ByteArray(paddedSize)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) padded[paddedSize - 8 + i] = (bitLength ushr (8 * i)).toByte()

        val m = IntArray(16)
        for (chunk in 0 until paddedSize step 64) {
            for (i in 0 until 16) {
                val j = chunk + i * 4
                m[i] = (padded[j].toInt() and 0xff) or ((padded[j + 1].toInt() and 0xff) shl 8) or
                    ((padded[j + 2].toInt() and 0xff) shl 16) or ((padded[j + 3].toInt() and 0xff) shl 24)
            }
            var a = a0; var b = b0; var c = c0; var d = d0
            for (i in 0 until 64) {
                val f: Int
                val g: Int
                when {
                    i < 16 -> { f = (b and c) or (b.inv() and d); g = i }
                    i < 32 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    i < 48 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                val sum = f + a + K[i] + m[g]
                a = d; d = c; c = b
                b += rotl(sum, S[i])
            }
            a0 += a; b0 += b; c0 += c; d0 += d
        }
        val out = ByteArray(16)
        intArrayOf(a0, b0, c0, d0).forEachIndexed { i, v ->
            for (k in 0 until 4) out[i * 4 + k] = (v ushr (8 * k)).toByte()
        }
        return out
    }
}

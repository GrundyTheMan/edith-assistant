package com.edith.app.core.util

/** Fills a byte array with unpredictable bytes (K2.6: the PKCE verifier and the OAuth state are secrets, so Kotlin's own `Random` will not do). */
fun interface RandomSource {
    fun nextBytes(bytes: ByteArray)
}

/** The system's secure random source: `SecureRandom` on Android and the desktop, `SecRandomCopyBytes` on the iPhone. */
expect fun systemRandom(): RandomSource

/** Whether two byte strings are equal, taking the same time wherever they differ (for comparing secrets such as the OAuth state). */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    var diff = a.size xor b.size
    for (i in 0 until minOf(a.size, b.size)) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

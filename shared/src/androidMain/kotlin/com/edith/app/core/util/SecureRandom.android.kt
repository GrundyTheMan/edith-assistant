package com.edith.app.core.util

import java.security.SecureRandom

actual fun systemRandom(): RandomSource {
    val random = SecureRandom()
    return RandomSource { random.nextBytes(it) }
}

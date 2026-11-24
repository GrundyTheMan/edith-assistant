package com.edith.app.core.util

/**
 * A re-entrant lock (K2.9: Kotlin has no `synchronized` outside the JVM). Java's `ReentrantLock` on Android and the desktop, `NSRecursiveLock`
 * on the iPhone. Use it through [synchronized], which unlocks even when the block throws or returns.
 */
expect class PlatformLock() {
    fun lock()
    fun unlock()
}

inline fun <T> PlatformLock.synchronized(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

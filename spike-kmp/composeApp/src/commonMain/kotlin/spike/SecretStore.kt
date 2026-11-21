package spike

/**
 * A small secret kept by the platform (changed only to start a warm-cache timing run): the iPhone's Keychain on iOS (a hand-written bridge to Apple's Security framework, which is the
 * kind of native work the real app would need many times), a plain map on the desktop. This is the spike's measure of interop effort.
 */
expect fun secretPut(key: String, value: String): Boolean

expect fun secretGet(key: String): String?

package com.edith.app.core.time

import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * Formats a moment for display with a Unicode date pattern such as `"EEE h:mm a"`, in [zone] and the locale [languageTag]
 * (a BCP 47 tag like `en-US`; null is the phone's own locale). Names and orders of things differ by locale, so each platform
 * formats with its own rules: java.time on Android and the desktop, `NSDateFormatter` on the iPhone (docs/design/kmp-transition.md §5).
 */
expect fun formatPattern(at: Instant, zone: TimeZone, pattern: String, languageTag: String? = null): String

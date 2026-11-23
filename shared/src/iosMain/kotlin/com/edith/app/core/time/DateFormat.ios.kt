package com.edith.app.core.time

import kotlinx.datetime.TimeZone
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.defaultTimeZone
import platform.Foundation.timeZoneWithName
import kotlin.time.Instant

actual fun formatPattern(at: Instant, zone: TimeZone, pattern: String, languageTag: String?): String {
    val formatter = NSDateFormatter()
    formatter.locale = if (languageTag == null) NSLocale.currentLocale else NSLocale(localeIdentifier = languageTag)
    formatter.timeZone = NSTimeZone.timeZoneWithName(zone.id) ?: NSTimeZone.defaultTimeZone
    formatter.dateFormat = pattern
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(at.toEpochMilliseconds() / 1000.0))
}

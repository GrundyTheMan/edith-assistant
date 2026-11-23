package com.edith.app.core.time

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toJavaInstant

actual fun formatPattern(at: Instant, zone: TimeZone, pattern: String, languageTag: String?): String {
    val locale = if (languageTag == null) Locale.getDefault() else Locale.forLanguageTag(languageTag)
    return DateTimeFormatter.ofPattern(pattern, locale).format(at.toJavaInstant().atZone(zone.toJavaZoneId()))
}

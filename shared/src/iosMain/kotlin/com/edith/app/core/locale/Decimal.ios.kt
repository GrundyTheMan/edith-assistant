package com.edith.app.core.locale

import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.currentLocale
import platform.Foundation.numberWithDouble

actual fun formatDecimal(value: Double, places: Int, languageTag: String?): String {
    val formatter = NSNumberFormatter()
    formatter.numberStyle = NSNumberFormatterDecimalStyle
    formatter.minimumFractionDigits = places.toULong()
    formatter.maximumFractionDigits = places.toULong()
    formatter.usesGroupingSeparator = false
    formatter.locale = if (languageTag == null) NSLocale.currentLocale else NSLocale(localeIdentifier = languageTag)
    return formatter.stringFromNumber(NSNumber.numberWithDouble(value)) ?: value.toString()
}

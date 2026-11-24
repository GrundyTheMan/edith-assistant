package com.edith.app.core.locale

import java.util.Locale

actual fun formatDecimal(value: Double, places: Int, languageTag: String?): String =
    String.format(if (languageTag == null) Locale.getDefault() else Locale.forLanguageTag(languageTag), "%.${places}f", value)

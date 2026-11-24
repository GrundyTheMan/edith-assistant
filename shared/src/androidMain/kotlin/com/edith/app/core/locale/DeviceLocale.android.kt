package com.edith.app.core.locale

actual fun deviceLanguageTag(): String = java.util.Locale.getDefault().toLanguageTag()

package com.edith.app.core.locale

/** The device's language as a BCP 47 tag, e.g. `en-US`. */
expect fun deviceLanguageTag(): String

/** The country in a language tag (`en-US` → `US`), or null when it names none. */
fun regionOf(languageTag: String): String? =
    languageTag.split('-', '_').drop(1).firstOrNull { it.length == 2 && it.all(Char::isLetter) }?.uppercase()

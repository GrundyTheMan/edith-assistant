package com.edith.app.core.locale

import kotlin.test.Test
import kotlin.test.assertEquals

/** K2.4: numbers are written the way the language does, on every platform (the iPhone uses NSNumberFormatter). */
class DecimalTest {
    @Test fun englishUsesAPoint() = assertEquals("219.8", formatDecimal(219.8, 1, "en-US"))

    @Test fun germanUsesAComma() = assertEquals("219,8", formatDecimal(219.8, 1, "de-DE"))

    @Test fun placesAreExact() {
        assertEquals("2.50", formatDecimal(2.5, 2, "en-US"))
        assertEquals("3", formatDecimal(2.6, 0, "en-US"))
    }

    @Test fun thereIsNoThousandsSeparator() = assertEquals("1234567.5", formatDecimal(1234567.5, 1, "en-US"))
}

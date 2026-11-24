package com.edith.app.core.locale

/**
 * [value] with exactly [places] digits after the point, written the way the [languageTag]'s language writes numbers ("219.8" or "219,8"), without a
 * thousands separator. A null tag is the phone's own language. (`String.format` is a Java function, so each platform brings its own.)
 */
expect fun formatDecimal(value: Double, places: Int, languageTag: String? = null): String

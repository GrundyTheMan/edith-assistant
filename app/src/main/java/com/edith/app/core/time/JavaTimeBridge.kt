package com.edith.app.core.time

import kotlinx.datetime.toJavaZoneId
import kotlin.time.toJavaInstant

/**
 * TEMPORARY (K1.3): the packages still on `java.time` (accounts, google, microsoft, icloud, jarvis, ...) read the clock through these.
 * Each package that moves to kotlinx-datetime drops its calls, and this file goes when the last one has (docs/design/kmp-transition.md §7).
 */
fun Clock.javaNow(): java.time.Instant = now().toJavaInstant()

fun Clock.javaZone(): java.time.ZoneId = zone().toJavaZoneId()

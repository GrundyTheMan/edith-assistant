package com.edith.app.tools.framework

import com.edith.app.core.time.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** A required argument is missing or can't be read; the loop sends the model a plain error instead (C08 §5). */
class ArgumentError(val argument: String, message: String) : Exception(message)

/**
 * A tool call's arguments, read tolerantly (C08 §2): numbers written as strings, "yes"/"no", dates with or without
 * an offset. If a key isn't there exactly, a declared alias or a case/underscore-insensitive match is accepted
 * ("Location" → "location"); nothing beyond the declared aliases is guessed.
 */
class ToolArgs(val raw: JsonObject, private val spec: ToolSpec) {

    fun string(name: String): String? =
        element(name)?.let { (it as? JsonPrimitive)?.content ?: it.toString() }?.trim()?.takeIf { it.isNotEmpty() } ?: missing(name)

    /** Like [string], but a value given as empty comes back as "" (C35: an empty `due` or `notes` clears it). */
    fun stringOrEmpty(name: String): String? =
        element(name)?.let { (it as? JsonPrimitive)?.content ?: it.toString() }?.trim()

    fun int(name: String): Int? = element(name)?.let { e ->
        val number = e.primitive(name).content.trim().toDoubleOrNull()
        if (number == null || number % 1.0 != 0.0) throw ArgumentError(name, "$name must be a whole number")
        number.toInt()
    } ?: missing(name)

    fun double(name: String): Double? = element(name)?.let { e ->
        e.primitive(name).content.trim().toDoubleOrNull() ?: throw ArgumentError(name, "$name must be a number")
    } ?: missing(name)

    fun bool(name: String): Boolean? = element(name)?.let { e ->
        when (e.primitive(name).content.trim().lowercase()) {
            "true", "yes", "y", "1", "on" -> true
            "false", "no", "n", "0", "off" -> false
            else -> throw ArgumentError(name, "$name must be true or false")
        }
    } ?: missing(name)

    /** ISO-8601 with an offset, or without one (then in [zone]), or a bare date (then its start). */
    fun dateTime(name: String, zone: TimeZone): Instant? = string(name)?.let { text ->
        parseMoment(text, zone) ?: throw ArgumentError(name, "$name must be a date and time like 2026-10-30T12:00")
    }

    /**
     * A day, never a time of day (C35: Google Tasks' due dates). Accepts `today`, `tomorrow`, `yesterday`, a weekday
     * name (the next one, today included; "next friday" skips today), `YYYY-MM-DD`, or an ISO date-time, of which the
     * date as written is taken (the model's `…T00:00:00Z` for "Friday" means Friday, not Thursday evening in
     * Michigan, L29). Relative words count from [clock]'s today. Anything else is an [ArgumentError].
     */
    fun day(name: String, clock: Clock): LocalDate? = string(name)?.let { raw ->
        parseDay(raw, clock.now().toLocalDateTime(clock.zone()).date)
            ?: throw ArgumentError(name, "$name must be a day like 2026-11-14, or today, tomorrow or a weekday")
    }

    private fun element(name: String): JsonElement? {
        raw[name]?.takeIf { it !is JsonNull }?.let { return it }
        val accepted = (listOf(name) + spec.parameters.firstOrNull { it.name == name }?.aliases.orEmpty()).map(::normalize)
        return raw.entries.firstOrNull { (key, value) -> value !is JsonNull && normalize(key) in accepted }?.value
    }

    private fun missing(name: String): Nothing? =
        if (spec.parameters.firstOrNull { it.name == name }?.required == true) throw ArgumentError(name, "missing $name") else null

    private fun JsonElement.primitive(name: String): JsonPrimitive =
        this as? JsonPrimitive ?: throw ArgumentError(name, "$name must be a single value")

    companion object {
        private fun normalize(key: String) = key.lowercase().replace("_", "").replace("-", "")

        private val DATE_PREFIX = Regex("^(\\d{4}-\\d{2}-\\d{2})(T.*)?$")

        /** An offset date-time, a zoned one (the `[Zone]` suffix is dropped: the offset says the moment), a local one (then in [zone]) or a bare date; null when it is none. */
        fun parseMoment(text: String, zone: TimeZone): Instant? =
            runCatching { Instant.parse(text.substringBefore('[')) }.getOrNull()
                ?: runCatching { LocalDateTime.parse(text).toInstant(zone) }.getOrNull()
                ?: runCatching { LocalDate.parse(text).atStartOfDayIn(zone) }.getOrNull()

        /** [day]'s rules on a text, with [today] given; null when it isn't a day. */
        fun parseDay(raw: String, today: LocalDate): LocalDate? {
            val text = raw.trim().lowercase().replace(Regex("\\s+"), " ")
            when (text) {
                "today" -> return today
                "tomorrow" -> return today.plus(1, DateTimeUnit.DAY)
                "yesterday" -> return today.plus(-1, DateTimeUnit.DAY)
            }
            val next = text.startsWith("next ")
            val word = text.removePrefix("next ").removePrefix("this ")
            DayOfWeek.entries.firstOrNull { it.name.lowercase() == word || it.name.lowercase().take(3) == word }?.let { dow ->
                var ahead = (dow.isoDayNumber - today.dayOfWeek.isoDayNumber + 7) % 7
                if (ahead == 0 && next) ahead = 7
                return today.plus(ahead, DateTimeUnit.DAY)
            }
            val m = DATE_PREFIX.find(raw.trim()) ?: return null
            m.groupValues[2].takeIf { it.isNotEmpty() }?.let { rest ->
                // A date-time: it must still be one, but only its date as written counts.
                val dt = m.groupValues[1] + rest
                if (parseMoment(dt, TimeZone.UTC) == null) return null
            }
            return runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull()
        }
    }
}

package com.edith.app.tools.framework

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

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
    fun dateTime(name: String, zone: ZoneId): ZonedDateTime? = string(name)?.let { text ->
        try {
            OffsetDateTime.parse(text).atZoneSameInstant(zone)
        } catch (_: DateTimeParseException) {
            try {
                ZonedDateTime.parse(text).withZoneSameInstant(zone)
            } catch (_: DateTimeParseException) {
                try {
                    LocalDateTime.parse(text).atZone(zone)
                } catch (_: DateTimeParseException) {
                    try {
                        LocalDate.parse(text).atStartOfDay(zone)
                    } catch (_: DateTimeParseException) {
                        throw ArgumentError(name, "$name must be a date and time like 2026-10-30T12:00")
                    }
                }
            }
        }
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

    private companion object {
        fun normalize(key: String) = key.lowercase().replace("_", "").replace("-", "")
    }
}

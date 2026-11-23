package com.edith.app.google.tasks

import kotlinx.datetime.LocalDate

/**
 * Google Tasks' due dates (C35 trap 1, L29). The API keeps only a date, sent and returned as midnight UTC
 * (`2026-11-05T00:00:00.000Z`). Reading that as an instant in Michigan would move it a day back, so the date part is
 * taken as it is. Every place that reads or writes `due` goes through these two functions.
 */
object TaskDue {
    /** The day in Google's `due`, or null when there's none or it can't be read. */
    fun fromGoogle(due: String?): LocalDate? {
        val day = due?.trim()?.take(10)?.takeIf { it.length == 10 } ?: return null
        return try {
            LocalDate.parse(day)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** [day] as Google wants it: midnight UTC of that date, never a time of day. */
    fun toGoogle(day: LocalDate): String = "${day}T00:00:00.000Z"
}

package com.edith.app.accounts.sources

import com.edith.app.core.error.Outcome

/**
 * One account's calendars with the owner's chosen default (Settings → Accounts → Default calendar, `Account.defaultCalendar`)
 * in place of the provider's: it's marked default and listed first, and a new event with no calendar named goes there.
 * A chosen calendar that's gone (deleted, or no longer writable) is ignored, so the provider's default applies again.
 */
class DefaultCalendarSource(private val inner: CalendarSource, private val chosen: String) : CalendarSource by inner {
    override suspend fun calendars(): Outcome<List<CalendarInfo>> = when (val r = inner.calendars()) {
        is Outcome.Failure -> r
        is Outcome.Success -> {
            val list = r.value
            if (list.none { it.id == chosen && it.canWrite }) r
            else Outcome.Success(list.map { it.copy(isDefault = it.id == chosen) }.sortedByDescending { it.isDefault })
        }
    }

    override suspend fun create(calendarId: String?, draft: EventDraft): Outcome<CalendarEvent> {
        if (calendarId != null) return inner.create(calendarId, draft)
        val usable = (inner.calendars() as? Outcome.Success)?.value?.any { it.id == chosen && it.canWrite } == true
        return inner.create(if (usable) chosen else null, draft)
    }
}

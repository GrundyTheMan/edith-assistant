package com.edith.app.accounts.sources

import com.edith.app.accounts.Account
import com.edith.app.accounts.Provider
import com.edith.app.core.error.Outcome
import kotlinx.coroutines.test.runTest
import com.edith.app.testing.assertEquals
import kotlin.test.Test
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** C24: the owner's default calendar (Settings → Accounts) over the provider's. */
class DefaultCalendarTest {
    private val start = LocalDateTime(2026, 11, 14, 15, 0).toInstant(TimeZone.of("America/Detroit"))

    private class Fake : CalendarSource {
        override val account = Account("i1", Provider.ICloud, "owner@icloud.com", "icloud")
        val created = mutableListOf<String?>()
        override suspend fun calendars() = Outcome.Success(
            listOf(
                CalendarInfo("bday", "Birthday calendar", isDefault = true),
                CalendarInfo("cal", "Calendar", isDefault = false),
                CalendarInfo("hol", "US Holidays", isDefault = false, canWrite = false),
            ),
        )
        override suspend fun events(from: Instant, to: Instant, text: String?) = Outcome.Success(emptyList<CalendarEvent>())
        override suspend fun create(calendarId: String?, draft: EventDraft): Outcome<CalendarEvent> {
            created += calendarId
            return Outcome.Success(CalendarEvent("e", calendarId ?: "bday", "x", "t", draft.start!!, draft.end!!, false))
        }
        override suspend fun update(event: CalendarEvent, change: EventChange) = Outcome.Success(event)
        override suspend fun delete(event: CalendarEvent) = Outcome.Success(Unit)
    }

    @Test fun `the chosen calendar is the default listed first and a new event goes there`() = runTest {
        val fake = Fake()
        val s = DefaultCalendarSource(fake, "cal")
        val cals = (s.calendars() as Outcome.Success).value
        assertEquals(listOf("Calendar", "Birthday calendar", "US Holidays"), cals.map { it.name })
        assertEquals(listOf(true, false, false), cals.map { it.isDefault })
        s.create(null, EventChange("Test", start, start + 1.hours))
        s.create("bday", EventChange("Named", start, start + 1.hours))
        assertEquals("unnamed → the choice; named → as asked", listOf<String?>("cal", "bday"), fake.created)
    }

    @Test fun `a choice that's gone or read-only falls back to the provider's default`() = runTest {
        for (gone in listOf("deleted", "hol")) {
            val fake = Fake()
            val s = DefaultCalendarSource(fake, gone)
            assertEquals("Birthday calendar", (s.calendars() as Outcome.Success).value.first { it.isDefault }.name)
            s.create(null, EventChange("Test", start, start + 1.hours))
            assertEquals(listOf<String?>(null), fake.created)
        }
    }
}

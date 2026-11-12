# C18 — Google Calendar: tools and Calendar screen

| | |
|---|---|
| Phase | 5 |
| Depends on | C17, C08 |
| Size | Medium–large |
| Read first | C08 (confirmations), C17, 06-decisions D11 |
| Since C22 | The tools work across every account and moved to `accounts/tools/` (`CalendarTools.kt`); the Calendar tab is `accounts/ui/`. New parameters (`account`, `calendar`), labels and the "which account?" rule: [C22](C22-several-accounts.md) §4 and As built. |

## Goal

"What's on my calendar tomorrow?", "Am I free Friday afternoon?", "Add lunch with Sam Friday at noon" (with
confirmation), "Move my dentist appointment to 3pm" (with confirmation) — against the owner's real Google
Calendar; plus a real Calendar tab.

## Specification

### 1. `CalendarClient` (REST over OkHttp, `google/calendar/`)
Base `https://www.googleapis.com/calendar/v3`. Bearer token from `GoogleAuth.accessToken(CalendarEvents)`.
- `calendarList()` → `GET /users/me/calendarList` (use calendars with `selected = true`; primary first).
- `events(calendarId, timeMin, timeMax, query?)` → `GET /calendars/{id}/events?timeMin=&timeMax=&singleEvents=true&orderBy=startTime&q=&maxResults=50`.
- `insert(calendarId, event)` → `POST /calendars/{id}/events`; `patch(…)`; `delete(…)`.
- Times RFC 3339 with offset; all-day events use `date`. Device zone for display and for interpreting
  user times. Capture real responses (event list, all-day event, recurring instance) as fixtures — verify the
  current API shape from Google's docs.

### 2. Tools (integration `google`)
| Tool | Params | Confirmation | Result |
|---|---|---|---|
| `list_calendar_events` | `start` DateTime, `end` DateTime, `query` String? | no | ≤ 20 events: `Fri Oct 30 12:00–1:00 PM — Lunch with Sam (Café X) [Personal]`; "No events." |
| `find_free_time` | `start`, `end`, `duration_minutes` Integer | no | free slots within working hours 8 AM–8 PM (setting later) |
| `create_calendar_event` | `title`, `start`, `end`?, `all_day` Boolean?, `location`?, `description`? | **yes**: "Add to your calendar?" with title, when, where | `Added "Lunch with Sam" on Fri Oct 30, 12:00–1:00 PM.` |
| `update_calendar_event` | `event_id`, any of title/start/end/location | **yes**, shows before → after | confirmation text |
| `delete_calendar_event` | `event_id` | **yes**, shows the event | confirmation text |
- `list` results include `event_id` (compact ids) so the model can update/delete what it listed.
- Default end = start + 1 hour; vague times ("Friday at noon") are resolved by the model to ISO (the prompt has
  the current date/time, C06); `ToolArgs.dateTime` assumes the device zone when no offset.
- Errors: 403 insufficient scope → `NeedsConsent` + message "I need permission to see your calendar — tap Grant access in Settings."

### 3. Calendar tab (`google/calendar/ui`)
- Not connected → explanation + "Connect Google account" (goes to Settings).
- Connected → agenda list for today + next 7 days (grouped by day, sticky headers), pull-to-refresh, tap an event
  for details. Read-only in v1 (editing happens through Edith with confirmation).

## Tests
- Fixture parsing (timed, all-day, recurring instance, missing location).
- Tool formatting; time-zone handling (event in another zone displayed in device zone).
- Confirmation required for create/update/delete; denial means no API call (MockWebServer sees none).
- 401 → re-authorize once; 403 → NeedsConsent.

## Acceptance criteria
- [x] Owner's account: "What's on my calendar tomorrow?" matches Google Calendar.
- [x] "Add a test event called Edith test tomorrow at 3pm" → confirmation card → Confirm → appears in Google Calendar;
      "Delete the Edith test event" → confirmation → removed. (Clean up afterwards.)
- [x] Calendar tab shows the same events.

## As built (2026-11-02)

Where the code differs from the specification above, the code is right.

**Calendars (the owner, 2026-11-02): all calendars checked in Google Calendar**, not only the primary; new events go
to the primary. Listing calendars needs a scope C17 didn't ask for (`calendar.events` doesn't cover
`calendarList.list`), so `GoogleScope.CalendarList` (`calendar.calendarlist.readonly`) was added. The owner added it in
the Console and approved it when reconnecting. Recorded in 06-decisions (D15).

**Files** (`google/calendar/`)
- `CalendarClient.kt`: calendars, events (all checked calendars, recurring events as instances, cancelled ones left
  out), insert, patch (only the changed fields), delete. All calls go through `GoogleAuth.authorized`.
- `CalendarTools.kt`:
  - `CalendarSession`: the calendars, cached 5 minutes, and short event handles (`e1`, `e2`…) that keep each listed
    event for the "before → after" card.
  - `CalendarFormat`, `ToolArgs.localTime`, and the five tools.
- `ui/CalendarScreen.kt`: `CalendarViewModel` and the tab.

Also:
- `GoogleCall.MissingScope` (a 403 `insufficientPermissions` → NeedsConsent).
- The Calendar tab in `EdithNavHost`, and `AppContainer.calendarClient`/`calendarSession` with the tools.
- C06's date line now includes the UTC offset.
- C08's date-time parameter description no longer shows a fixed offset.

**Differences from the spec**
- **Event ids:** results show `{event_id: e3}`, short handles kept for the app run (up to 300), not Google's ids. An
  unknown handle → "list the events first", and no request is made.
- **Time zones (live findings):**
  - gemma4 wrote "tomorrow" as `…T00:00:00Z`–`…T23:59:59Z` (a day shifted 5 hours), and "tomorrow at 3pm" as
    `15:00-04:00` the day after daylight saving ended. The card said 2:00 PM, and it was cancelled.
  - Now the date line gives the offset (`America/Detroit, UTC-05:00`), the parameter example has no offset, the
    calendar guidance says "local time without an offset or Z", and `localTime` reads `Z`/`+00:00` and the owner's
    own zone's offsets (summer or winter) as wall-clock time. A different zone's offset (`-08:00`) is kept.
  - See L29.
- **Free time:** 8 AM–8 PM each day; all-day events (holidays, birthdays) don't block.
- **Update:** a new start keeps the event's length unless an end is given. Only the changed fields are sent.
- **Confirmation cards:**
  - Add: "Add to your calendar?" with What/When/Where.
  - Change: "Change this event?" with before → after.
  - Delete: "Delete from your calendar?".
  - The buttons say Add/Change/Delete.
- **Errors:** a missing scope → NeedsConsent and "tap Grant access in Settings"; 404/410 → "That event isn't in your
  calendar any more."
- **The Calendar tab** shows days that have events (an empty week says so).
  - Not connected → "Connect Google account"; needing permission → "Grant access in Settings". Both open Settings.

**Verified**
- `scripts/check.sh --device`: OK (`build-logs/2026-11-02_13-21-47_check.log`). 195 unit tests (15 new in
  `CalendarTest`), lint clean, 107 device tests (2 new in `CalendarScreenTest`; `EdithNavHostTest` updated).
  - `CalendarTest` (JVM, MockWebServer; the responses follow Google's API reference, made up):
    - checked calendars, primary first, with `summaryOverride`
    - events in the device zone: a Pacific-time event, all-day, a recurring instance, missing location, cancelled
      left out
    - query parameters
    - a 401 cleared and retried; a 403 missing scope → NeedsConsent
    - list lines and handles; "No events."
    - a UTC day and a summer offset read as local, another zone kept
    - free-time gaps
    - create with its card and JSON (timed and all-day); update before → after with only `start`/`end` sent; delete
    - an unknown id sends nothing
    - **declining the card through the real tool loop sends no request**
    - formats
- **Live on the emulator** (the owner's account; the owner tapped Add and Delete themselves):
  - "What's on my calendar tomorrow?" / "this week?" → the all-day Election Day holiday only. The owner confirmed it
    matches Google Calendar.
  - "Add a test event called Edith test tomorrow at 3pm":
    - first try: the card said 2:00 PM (the offset bug), cancelled
    - after the change: the card said Tue Nov 3, 3:00–4:00 PM → Add → "Added…"
    - the Calendar tab showed it at 3:00 PM
  - "Delete the Edith test event" → Edith listed it, then the card → Delete → "Deleted…". After a pull-to-refresh the
    tab showed only Election Day. The owner confirmed it's gone from Google Calendar.
  - Reconnecting asked for consent again, which also confirms C17's Disconnect had revoked access.
  - No crash.
- **Not verified live:** find_free_time and update_calendar_event against the real calendar (unit-tested), and a real
  401 or 403.

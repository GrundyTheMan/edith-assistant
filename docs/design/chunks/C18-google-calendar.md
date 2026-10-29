# C18 — Google Calendar: tools and Calendar screen

| | |
|---|---|
| Phase | 5 |
| Depends on | C17, C08 |
| Size | Medium–large |
| Read first | C08 (confirmations), C17, 06-decisions D11 |

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
- [ ] Owner's account: "What's on my calendar tomorrow?" matches Google Calendar.
- [ ] "Add a test event called Edith test tomorrow at 3pm" → confirmation card → Confirm → appears in Google Calendar;
      "Delete the Edith test event" → confirmation → removed. (Clean up afterwards.)
- [ ] Calendar tab shows the same events.

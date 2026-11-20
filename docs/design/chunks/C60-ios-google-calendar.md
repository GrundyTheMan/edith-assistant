# C60 — Calendar on iOS: Google Calendar, the calendar tools across accounts, and the Calendar tab

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave B |
| Status | **Design** |
| Depends on | C59 (accounts, Google sign-in), C45 (confirmations) |
| Unblocks | C62 (to-dos share the tab), C63 Microsoft, C64 iCloud calendar, C66 Jarvis work calendar |
| Runs in | the development build |
| Size | Large |
| Ports | C18 (`CalendarClient`, tools, tab) and C22 §3–4, §6 (the provider-neutral tools, the Calendar tab) |
| Read first | C18 spec **and** As built, C22 §3–4 and As built (tool parameters, labels, de-duplication, partial failure), 04-lessons **L29** (time zones) and L6, `chat/promptBuilder.ts` (`describeMoment`), `tools/context/currentTimeTool.ts` (`zoneOffsetMinutes`) |

## Why
"What's on my calendar tomorrow?", "Am I free Friday afternoon?", "Add lunch with Sam Friday at noon" (a card), "Move my
dentist appointment to 3pm" (a card), plus a **Calendar tab**. Tools are provider-neutral (they read every connected account);
this chunk implements the Google source, the tools and the tab. Microsoft, iCloud and Jarvis add sources later with no
changes to the tools.

## Specification

### 1. Time handling first (04-lessons L29: time zones and daylight saving bit the earlier apps)
`tools/time.ts` (pure, with tests): the **phone's zone** comes from `Intl.DateTimeFormat().resolvedOptions().timeZone` (verify Hermes returns an IANA name on the owner's phone; C41 already depends on it: reuse `Clock.zone` and `zoneOffsetMinutes`).
- `parseLocalTime(text, clock)`: an ISO string **with an offset** is taken as is; **without** one it is interpreted in the phone's zone (DST-aware: compute the offset **for that date**, not today's — the L29 bug); a date-only value means local midnight; `today`/`tomorrow` words are not parsed (the model resolves them from the prompt's date line).
- `formatEventTime(start, end, allDay, clock)`: `Fri Oct 30, 12:00–1:00 PM` in the phone's zone, 12-hour; all-day: `Fri Oct 30 (all day)`; multi-day all-day: `Oct 30–Nov 2`.
- Test every case across a DST change (2026-03-08 and 2026-11-01 in `America/Detroit`), an event in another zone shown in the phone's zone, and a phone in a zone with a half-hour offset.

### 2. `GoogleCalendarSource` (`google/calendar.ts`, REST over `secureFetch`, base `https://www.googleapis.com/calendar/v3`)
Implements `CalendarSource` (C59): `calendars()`, `events(from, to, text)`, `create`, `update`, `delete`. Token from `GoogleAuth.authorized`.
- **Calendars:** `GET /users/me/calendarList`: use `selected = true` ones (D15: all calendars the owner has checked in Google Calendar); primary first; `canWrite` from `accessRole` (`owner`/`writer`). Needs the scope `calendar.calendarlist.readonly` (C59 requests it).
- **Events:** `GET /calendars/{id}/events?timeMin=&timeMax=&singleEvents=true&orderBy=startTime&q=&maxResults=50` for each selected calendar, in parallel; **recurring events as instances**; cancelled ones left out. Times RFC 3339; all-day events use `date`.
- `insert` → `POST /calendars/{id}/events`; `patch` (only changed fields); `delete`.
- Errors: 401 → one refresh + retry (C59); 403 → needs grant; 404 on update/delete → "That event no longer exists."
- Capture real response shapes as fixtures (timed, all-day, recurring instance, missing location) — **ask the owner for one sample of each from their own calendar only if needed**, else use Google's documented shapes and mark them "made up from the docs".

### 3. Tools (integration `accounts`; each offered while at least one account has a calendar)
Same table as C18/C22, in TypeScript with `ToolSpec`:
| Tool | Params | Card | Result |
|---|---|---|---|
| `list_calendar_events` | `start`, `end` (datetime), `query?`, `account?` | no | ≤ 20 events: `Fri Oct 30 12:00–1:00 PM — Lunch with Sam (Café X) [e3] [personal]`; "No events." The account label only when more than one account. The same event in two accounts (an invite: same `iCalUid` + start) is listed once with both labels. |
| `find_free_time` | `start`, `end`, `duration_minutes`, `account?` | no | free slots within 8 AM–8 PM; busy in any account is busy |
| `create_calendar_event` | `title`, `start`, `end?`, `all_day?`, `location?`, `description?`, `account?`, `calendar?` | **yes**: "Add to your calendar?" rows What, When, Where, **Account** ("work (sam@contoso.com) — Calendar") | `Added "Lunch with Sam" on Fri Oct 30, 12:00–1:00 PM.` |
| `update_calendar_event` | `event_id` (the handle), any of title/start/end/location | **yes**, before → after, with the account | text |
| `delete_calendar_event` | `event_id` | **yes**, shows the event and account | text |
- **Handles** (`e1`, `e2`…): a per-run counter; the registry maps a handle to `{accountId, calendarId, eventId}`; an unknown handle → "That event isn't in my list any more; list the events again."
- Default end = start + 1 h. A start with no offset → phone zone (§1). `all_day` → date-only.
- **Which account** for a new event: C59's `whichAccount` rule, in the tool's `execute` and `confirmation` (return the "Ask the owner which account…" sentence before any card). The card always names the account.
- **Partial failure** line from `Reads` (C59).
- **A denied card means no request** (a test: the fake HTTP sees none).
- Prompt: `accountsSection` names the accounts; tool descriptions say "Times are the owner's local time."

### 4. The Calendar tab (`app/(tabs)/calendar.tsx`, `ui/CalendarScreen.tsx`; tab order **Chat, Calendar, Home, Settings**)
- Not connected → "Connect an account to see your calendar" with a button to Settings → Accounts.
- Connected → an **agenda**: today and the next 7 days, grouped by day with sticky headers (`SectionList`, `stickySectionHeadersEnabled`), each event: time range, title, location, a coloured dot or small label for the account when more than one; pull-to-refresh; tap an event → a sheet with details (read-only; changes go through Edith with a card).
- All accounts merged by time (the same invite once); an account that can't be read shows a one-line banner with a **Settings** button (as C18).
- (C62 adds the **Agenda | To-do** switch at the top.)
- Refresh when the tab gains focus (`useFocusEffect`) and at most once a minute.

## Files
New: `tools/time.ts`, `google/calendar.ts`, `accounts/tools/calendarTools.ts`, `accounts/mergeEvents.ts`, `accounts/handles.ts`, `ui/CalendarScreen.tsx`, `app/(tabs)/calendar.tsx`, tests for each. Changed: `core/services.tsx`, `app/(tabs)/_layout.tsx`.

## Tests
- Fixtures: timed, all-day, recurring instance, missing location, an event in another zone shown in the phone's zone.
- Time parsing/formatting across DST (§1).
- Tool formatting and the 20-event cap; `find_free_time` across two fake sources; the de-duplication of one invite in two accounts.
- **Card required** for create/update/delete; **denial → the fake HTTP sees no write** (assert zero POST/PATCH/DELETE requests).
- `whichAccount` through the real tool loop: no card and no request when Edith must ask.
- 401 → refresh once, 403 → needs grant; handle unknown message; partial failure line; handles stay with their account.
- Tab: not-connected state, grouping, banner, an event sheet.

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green.
- [ ] **Owner:** "What's on my calendar tomorrow?" matches Google Calendar; "Add a test event called Edith test tomorrow at 3pm" → Edith asks which account when two exist → the card names it → Confirm → it appears there only; "Delete the Edith test event" → card → gone (clean up); the Calendar tab shows the same events; times are right on a date across a DST change.

## Risks and unverified assumptions
- `Intl` time zone support in Hermes on the owner's iPhone (C41 relies on it; verify and record). If `resolvedOptions().timeZone` is wrong, fall back to the `Date` offset for the date in question and say so.
- The model resolving "Friday at noon": judge with `gemma4:12b`.

## Not in this chunk
Editing recurring series beyond the single instance (note it in the tool text), attendees/invitations, calendar creation, notifications/reminders.

# C62 — To-dos on iOS: Google Tasks

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave B |
| Status | **Design** |
| Depends on | C59 (accounts), C60 (the Calendar tab, to add the switch), C45 (cards) |
| Unblocks | C66 (the Jarvis work to-dos) |
| Runs in | the development build |
| Size | Large |
| Ports | C35 (all of it: `google/tasks/`, `accounts/tasks/`, D20, Jarvis's tools) |
| Read first | C35 spec **and** As built (the eight tools, the suggested-order rules, the scope-on-demand rule, `TaskDue`, `task-ranks.json`), D20, the Jarvis sources it names (`tasks` tools and ranking prompt) |

## Why
"What are my to-dos?", "Add 'call the dentist' for Friday", "Tick off the dentist", "Suggest an order for my list". Google
Tasks in every connected Google account, with Jarvis's behaviour (D20): list, add, change, tick off, delete, move, and a
**suggested order** from the local model that is applied only when the owner asks. Every change is on a card (D11). Microsoft
To Do and iCloud Reminders are not included (possible later chunks).

## Before you start — owner setup
Enable the **Google Tasks API** in the Cloud project and add the `…/auth/tasks` scope to the consent screen (`docs/google-setup.md`). Existing accounts will show "To-dos: Grant access" until the owner grants it (scope on demand, as C35).

## Specification

### 1. `TaskSource` (C59 interface) and `GoogleTasksSource` (`google/tasks.ts`, `https://tasks.googleapis.com/tasks/v1`)
- `lists()`: `GET /users/@me/lists`. `tasks(listId, includeDone)`: `GET /lists/{id}/tasks?showCompleted=…&showHidden=…&maxResults=100` (follow `nextPageToken` up to 5 pages; cap 200 tasks per list for tool output). Fields: `id`, `title`, `notes`, `due` (RFC 3339 **date only in practice**), `status` (`needsAction` | `completed`), `parent`, `position`.
- `add` → `POST /lists/{id}/tasks` (`parent` as a query parameter for subtasks); `change` → `PATCH /lists/{id}/tasks/{task}`; `complete` → patch `status` + `completed` (clear it when un-ticking); `delete` → `DELETE`; `move` → `POST /lists/{id}/tasks/{task}/move?parent=&previous=`.
- **`TaskDue`** (`google/taskDue.ts`, pure): Tasks stores a date as midnight UTC; **never** shift it by the phone's zone (a to-do due "Fri Nov 14" stays Nov 14): parse/format by the **UTC date components** only. Test across zones east and west of UTC.
- **Scope on demand:** the first Tasks call on an account without the scope returns `needs_sign_in` with the reason "To-dos: Grant access" and the account row offers **Grant access** (an incremental authorization with `include_granted_scopes=true`, C59).

### 2. The eight tools (integration `accounts`; offered while an account with Tasks exists)
| Tool | Params | Card | Result |
|---|---|---|---|
| `list_tasks` | `list?`, `include_done` = false, `account?` | no | grouped by list; `- Call the dentist (due Fri Nov 14) {t3}`; a note's first 100 characters after a dash |
| `add_task` | `title`, `notes?`, `due?` (a date, `today`, `tomorrow`, or `YYYY-MM-DD`), `list?`, `parent_task_id?`, `account?` | **yes** "Add to your to-dos?": What / Due / List (with the account) | `Added "…" to My Tasks.` |
| `change_task` | `task_id`, any of `title`/`notes`/`due` (empty clears) | **yes**, before → after | text |
| `complete_task` | `task_id`, `done` = true | **yes** "Tick this off?" / "Mark this not done?" | text |
| `delete_task` | `task_id` | **yes** "Delete this to-do?" (the description says to prefer `complete_task` for finished tasks, as Jarvis does) | text |
| `move_task` | `task_id`, `to` (`top`/`bottom`/`above`/`below`), `other_task_id?` | **yes** "Move this?" with where | text |
| `suggest_task_order` | `list?`, `account?` | no (changes nothing) | §3 |
| `apply_task_order` | `list`, `account?` | **yes**: old order → new order, numbered | `Reordered My Tasks.` |
- **Which account** for a new task follows D17 exactly (C59 `whichAccount`): the owner named one; a named list only one account has; only one account has Tasks; else "Ask the owner which account to use…".
- Handles `t1`… per run; `parent_task_id` uses a handle.

### 3. Suggested order (Jarvis's)
`suggest_task_order(list)`: build a prompt from the open tasks (titles, notes, due dates, today's date), ask **the same local model** through `OllamaClient.chat` (non-streaming, no tools) for an ordered list of task numbers (use Jarvis's prompt text: **copy it from `D:\Dev Projects\jarvis`'s tasks ranking code, with attribution in a comment**, and re-check the file first); parse tolerantly (numbers separated by commas/newlines; drop unknown numbers; append any missing at the end). Save the ranking **per list until the list changes** (hash of task ids + titles + due) in the key-value store (`task_ranks`, JSON; Android used `task-ranks.json`). The tool returns the suggested order as numbered lines **and says it hasn't changed the list**. `apply_task_order` is the **only** thing that changes the list and only when the owner asks; it applies with `move` calls (minimise moves), and a card shows old → new.

### 4. Calendar tab: **Agenda | To-do** switch
A segmented control at the top of the Calendar tab (C60). The To-do view: lists per account (section headers with the account label when more than one), tasks with due dates (overdue in the error colour), a checkbox that ticks a task. **A tap on a checkbox is the owner's own action, so it needs no confirmation card** (D11 is about what Edith does; write this decision in the PR), pull-to-refresh, show/hide completed. No editing UI beyond ticking (changes go through chat).

## Files
New: `google/tasks.ts`, `google/taskDue.ts`, `accounts/tools/taskTools.ts`, `accounts/taskRank.ts`, `ui/TodoView.tsx`, tests. Changed: `ui/CalendarScreen.tsx`, `core/services.tsx`, `google/auth.ts` (the Tasks scope on demand).

## Tests
`TaskDue` across zones; list/paging fixtures (tasks, subtasks, completed, no due); every tool's text and card; denial → no request; which-account branches (named, list only one has, only one account has Tasks, ask); the scope-missing path shows "Grant access"; ranking prompt building, tolerant parsing (junk, duplicates, missing numbers), caching until the list changes, `apply` doing minimal moves and producing the final order; the To-do view (grouping, overdue, tick sends one PATCH).

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green.
- [ ] **Owner:** after "Grant access": "What are my to-dos?" matches Google Tasks; "Add a to-do to call the dentist Friday" → card → appears in Google Tasks with the right date (no off-by-one); "Tick off the dentist" → card → done; "Suggest an order" → a list and the sentence that nothing changed; "Apply it" → card → the order changes; the Agenda | To-do switch shows the same tasks.

## Risks and unverified assumptions
- The scope grant on an existing account may force a full browser sign-in; fine.
- Jarvis's ranking prompt may have changed since Android copied it: re-read it.

## Not in this chunk
Microsoft To Do, iCloud Reminders (their own chunks if wanted), reminders/notifications, recurring tasks (Google Tasks has none), the Jarvis work to-dos (C66).

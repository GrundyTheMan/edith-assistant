# C06b — Multiple conversations (storage and engine)

| | |
|---|---|
| Phase | 1 |
| Status | **Done** — see "As built" at the end (it differs from §1–§3 in places) |
| Depends on | C05, C06 |
| Unblocks | C07 (which adds the conversation list UI, C07 §8) |
| Size | Medium |
| Read first | 06-decisions D6, D12; 01-architecture §5.1; C05 and C06 "As built" |

## Goal

Decision D6 (2026-10-31) is **multiple conversations**: a conversation list plus "New chat". C05 and C06
were built for a single conversation (`ChatRepository.CONVERSATION_ID = 1L`). This chunk makes storage
and the chat engine work with any number of conversations, so C07 only has to build screens on top.
**No UI here** (C07 does the list, switching, rename and delete).

Branch `c06b-multiple-conversations`, PR titled `C06b: Multiple conversations`.

## Specification

### 1. Schema version 2 (`data/db/`) — a real migration
The owner's emulator already has a version-1 `edith.db` with chat history. **Migrate it; never fall back
to destructive migration** (`fallbackToDestructiveMigration` is forbidden).

`conversations` becomes:
```sql
conversations(
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  title       TEXT,                        -- NULL = not titled yet (shown as "New chat")
  title_set_by_owner INTEGER NOT NULL DEFAULT 0,  -- 1 after a rename; auto-titling never overwrites it
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL             -- newest message time (or created_at); the list sorts by it
)
-- new index: conversations(updated_at)
```
- `MIGRATION_1_2` (hand-written `Migration`, not an AutoMigration — the primary key changes to
  AUTOINCREMENT and `title` becomes nullable, which needs a table rebuild): create the new table, copy rows
  (`updated_at` = newest `messages.created_at` for that conversation, else `created_at`;
  `title_set_by_owner` = 0), drop the old table, rename. Keep existing ids, so every message stays attached.
  Then run `PRAGMA foreign_key_check` and fail the migration if it reports anything.
- **Danger:** `messages` references `conversations` with `ON DELETE CASCADE`. If foreign keys are ON when
  the old table is dropped, SQLite deletes **every message**. `PRAGMA foreign_keys` can't be changed inside
  the migration's transaction, so first check whether it is ON during a Room migration. Don't assume either
  way. If it can be ON, rebuild without dropping a referenced table (for example, rebuild `messages` too).
  The migration test must prove messages survive.
- The existing conversation 1 keeps its title "Edith".
- Export `app/schemas/.../2.json`; keep `1.json`. Add `room.testing` migration tests (the dependency is
  already in the catalog); add `schemas` to the androidTest assets so `MigrationTestHelper` finds them.
- `EdithDatabaseCallback.onCreate` (fresh installs) creates one empty, untitled conversation instead of
  "Edith".
- Update 01-architecture §5.1 to schema version 2 (and replace the "v1 uses one conversation" comment).

### 2. `ConversationDao` (new)
```kotlin
@Query(/* conversations + last message preview, newest updated_at first */)
fun observeAll(): Flow<List<ConversationSummary>>
suspend fun insert(c: ConversationEntity): Long
suspend fun get(id: Long): ConversationEntity?
suspend fun rename(id: Long, title: String)             // sets title_set_by_owner = 1
suspend fun setAutoTitle(id: Long, title: String)       // only WHERE title IS NULL AND title_set_by_owner = 0
suspend fun touch(id: Long, at: Long)                   // updated_at
suspend fun delete(id: Long)                            // messages and attachment rows cascade
suspend fun isEmpty(id: Long): Boolean
suspend fun deleteEmptyExcept(keepId: Long)
```
`ConversationSummary(id, title: String?, updatedAt: Long, lastMessagePreview: String?)`. The preview is the
newest Complete message's text, first ~80 chars, single line.

### 3. `ChatRepository` takes a conversation id
Remove `CONVERSATION_ID` and the `conversationId` property. Every message function takes `conversationId:
Long` as its first parameter: `observe`, `recent`, `latest`, `addUserMessage`, `addAssistantMessage`,
`addFailedAssistantMessage`, `addCancelledAssistantMessage`, `clearConversation`. Add the conversation
functions (`conversations()`, `createConversation()`, `rename`, `delete`) on top of `ConversationDao`.
- Each message insert also bumps `updated_at`, **in the same transaction** (`db.withTransaction`).
- **Auto-title**: the first user message in an untitled conversation sets its title in that
  transaction: the message trimmed, whitespace collapsed, cut to 40 characters at a word boundary with "…"
  (D12). Voice memos without text (C12) leave it untitled until there is text.
- **Inserting into a conversation that no longer exists** (deleted mid-turn) must not crash: catch the
  foreign-key `SQLiteConstraintException`, log a warning, return without inserting. (See §5.)

### 4. The current conversation (`chat/CurrentConversation.kt`, new)
```kotlin
class CurrentConversation(repo: ChatRepository, settings: SettingsRepository, scope: CoroutineScope) {
    val id: StateFlow<Long?>          // null only until startup has resolved it
    suspend fun select(id: Long)
    suspend fun newChat(): Long       // reuses the current conversation if it's already empty
}
```
- Persist the selection in settings (`Settings.currentConversationId: Long?`, C04's DataStore), so the
  app reopens on the last chat.
- On startup: use the saved id if that conversation exists, else the most recently updated one, else
  create one. Then `deleteEmptyExcept(current)`, so abandoned "New chat"s don't pile up.
- Lives in `AppContainer`. ViewModels read it; no composable creates it.

### 5. `ChatEngine` binds each turn to its conversation
- `send(conversationId, text, source, attachments)` and `regenerateLast(conversationId)`. The id is
  captured when the turn starts; **every** read and write in that turn uses it, even if the owner
  switches chats mid-reply.
- `EngineState.Working`, `AwaitingConfirmation` and `Failed` gain `conversationId`, so C07 shows the
  in-flight bubble (and the failure state) only in the conversation it belongs to.
- Still **one turn at a time across all conversations** (one Ollama model; v1's busy rule unchanged).
  While a reply runs in chat A, chat B can't send: `canSend` false, with a hint (C07).
- `suspend fun cancelAndJoin(conversationId)`: cancels the turn if it belongs to that conversation and
  waits until its cancelled message is stored. Deleting a conversation calls this **before** deleting it,
  so the cancelled-reply insert can't land in a deleted conversation. §3's crash guard is the backstop.
- `ReplyListener` callbacks get the conversation id too (C13 speaks only the open conversation's reply).

### 6. Effects on later chunks (update their docs in this PR)
- **Attachments (C15/C16)**: "Attachment N" numbers are per conversation (already true in
  `AttachmentDao.nextNumber`). **This is a bug in C05's code**: `AttachmentDao.byNumber(number)` ignores the
  conversation. Change it to `byNumber(conversationId, number)` (join through `messages`). Retention
  (default 10, O1) counts across **all** conversations, which `storedNewestFirst` already does. Deleting a
  conversation must also delete its stored attachment files: C16 owns the files, so add that to C16's spec.
- **Hands-free (C14)** and **voice memos (C12)** go into the current conversation.
- **Tools (C08)**: no change; context is per conversation already.

## Tests
- Migration: a v1 database with conversation 1 and messages → v2. Same ids and messages,
  `updated_at` = newest message time, title "Edith", `title_set_by_owner` 0, `foreign_key_check` clean.
  Also v1 with an empty conversation.
- Repository: two conversations stay separate (observe, recent, latest, clear). An insert bumps
  `updated_at` and reorders `observeAll`. Auto-title rules: first message only, 40 chars at a word
  boundary, never over an owner rename. Insert into a deleted conversation → no crash, nothing stored.
- `CurrentConversation`: restores the saved id; falls back when it was deleted; `newChat()` reuses an
  empty current chat; empty non-current chats are pruned at startup.
- Engine (extend `ChatEngineTest`): a turn started in A finishes in A after selecting B; B can't send
  while A runs; state carries the right id; `cancelAndJoin` then delete A → no crash, nothing orphaned.
- `byNumber` returns the attachment from the right conversation when two conversations share a number.

## Acceptance criteria
- [ ] Install over the owner's existing version-1 app (`adb install -r`, **not** uninstall): the
      old chat history is all there in conversation 1, and logcat shows no migration error.
- [ ] `scripts/check.sh --device` passes; lint clean; app launches with no crash.
- [ ] 01-architecture §5.1, C07, C14, C15, C16 and the README updated to match.

## Pitfalls
- Migrations run once. Test them against a real v1 file (the exported `1.json` via `MigrationTestHelper`),
  not only a fresh v2 database.
- Don't bump the schema version without exporting `2.json`, or the build passes and the migration test
  silently has nothing to check against.
- Don't let a mid-turn switch move the reply: capture the id once, at the start of the turn.

## As built (2026-10-31)

Where the code differs from the specification above, the code is right. The differences are deliberate.

- **Migration: ADD COLUMN, no table rebuild.** §1 planned a rebuild (AUTOINCREMENT id, nullable title). That
  would drop a table `messages` references with `ON DELETE CASCADE`, and could delete every message. Instead
  `MIGRATION_1_2` only adds `title_source` and `updated_at` and an index, so nothing referenced is dropped:
  - `id` stays a plain `INTEGER PRIMARY KEY`; `ConversationDao.insertNew` inserts without an id and SQLite
    assigns one. A deleted newest conversation's id can be reused; nothing depends on ids being unique forever.
  - `title` stays `NOT NULL`; `''` means untitled.
- **`title_source` instead of `title_set_by_owner`** (`none` / `first_message` / `edith` / `owner`), because
  the owner chose D12 = Edith writes titles. `ConversationDao.setTitle` ranks them (owner > edith >
  first_message): a lower source never replaces a higher one, and a first-message title is set only once.
  The migrated conversation 1 keeps "Edith" as an `owner` title.
- **`EdithDatabaseCallback` is gone.** A fresh install starts with no conversations; `CurrentConversation.resolve()`
  creates the first one at startup (`AppContainer.openDatabaseInBackground`).
- **Repository inserts return `Long?`**: null when the conversation was deleted mid-turn (the foreign-key
  `SQLiteConstraintException` is caught and logged). The engine also stops a turn whose user message couldn't be
  stored.
- **`CurrentConversation`** (in `chat/`) also owns `delete(id)`: `engine.cancelAndJoin(id)`, delete, then open the
  most recent remaining chat (or a new one). It takes the engine instead of a scope.
- **D12 titler** (`chat/engine/ConversationTitler.kt`): a `ReplyListener` registered in `AppContainer`. After a
  reply finishes in a chat that has no Edith or owner title, it makes one non-streaming `chat` call (same model,
  system instruction "2 to 6 words…") off the turn, in `appScope`, and cleans the answer (`clean`: first line,
  no quotes, markdown, "Title:" or ending punctuation, ≤ 60 chars). One attempt per chat per app run. On failure
  the first-message title stays.
- `ReplyListener` callbacks take the conversation id first; `ChatEngine.activeConversationId` tells the UI which
  chat a running turn belongs to.

**Verified**
- `scripts/check.sh --device`: OK (41 device tests), 68 unit tests, lint clean (`build-logs/2026-10-31_19-58-18_check.log`).
- New tests:
  - `MigrationTest`: v1 file with messages and an attachment → v2, all rows kept, `foreign_key_check` clean;
    then opened by Room as the app does.
  - `ChatRepositoryTest`: 7 new (separation, list order, title ranking, delete cascade, insert after delete,
    `byNumber` per conversation, pruning).
  - `ChatEngineTest`: 6 new (reply stays in its chat across a switch, one turn across chats, failures name
    their chat, delete mid-reply, `CurrentConversation` restore/fallback/new-chat reuse, titler success and
    failure).
  - `ConversationTitleTest` (JVM): title cutting and cleaning.
- Installed over the owner's emulator app with its version-1 `edith.db` (backed up first): it migrated to
  version 2, conversation 1 "Edith" kept, the app launched with no crash. That database had no messages (no chat
  UI yet), so message survival rests on `MigrationTest`.
- Live (`ChatEngineLiveCheckTest`, `-e live true`): first token 133 ms with the model loaded, and Edith's title
  ("Friendly Greeting Interaction") in 161 ms.

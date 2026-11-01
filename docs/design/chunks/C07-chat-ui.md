# C07 — Chat UI

| | |
|---|---|
| Phase | 1 |
| Status | **Done** — see "As built" at the end |
| Depends on | C06, C06b (and C01 navigation) |
| Unblocks | C08 (confirmation card), C12/C13 (input row and action row hooks), C15 |
| Size | Large (chat screen plus the conversation list, §8) |
| Read first | 00-product §4.7; 01-architecture §10; 02-standards §2 (content descriptions); C06b; 06-decisions D6, D12 |

## Goal

The chat screen the owner approved in v1, rebuilt cleanly: bubbles on opposite sides, streaming reply
with a status label, compact input row, action row hugging Edith's bubbles, timestamps, auto-scroll,
keyboard-aware layout, clear error display.

## Specification

### 1. `ChatViewModel`
Follows `CurrentConversation.id` (C06b) and combines `ChatRepository.observe(id)` with `ChatEngine.state`.
Engine states for another conversation don't show here: they only make `canSend` false, with the hint
"Edith is replying in another chat". The ViewModel produces
```kotlin
data class ChatUiState(
    val messages: List<MessageUi>, val inFlight: InFlight?,  // Working/AwaitingConfirmation rendered as a trailing bubble
    val error: String?, val canSend: Boolean, val draft: String,
)
data class MessageUi(val id: Long, val role: MessageRole, val text: String, val time: String, val status: MessageStatus,
                     val toolsUsed: List<String>, val attachments: List<AttachmentUi>, val isLatestAssistant: Boolean)
```
Created via factory from `AppContainer` (lessons L10). Draft text lives in `SavedStateHandle` (survives rotation and process death).

### 2. Layout (`chat/ui/ChatScreen.kt`)
- `LazyColumn` of messages, `contentPadding 12.dp`, `spacedBy(8.dp)`; empty state centred:
  "Ask Edith anything — your schedule, the weather, or what's on your mind."
- Auto-scroll to the bottom when a message is added, when streaming text grows, and when the last
  message's content changes (regenerate replaces it without changing the count — v1 bug).
  Don't yank the user down if they've scrolled up more than one screen; show a "↓ New" FAB instead.
- `Modifier.imePadding()` on the column; the Scaffold's insets consumed (keyboard pushes the input up,
  the bottom bar stays behind the keyboard).
- Error line (from `ChatUiState.error`) above the input in `colorScheme.error`, dismissible.

### 3. Bubbles (`MessageBubble`)
- User: right-aligned, `colorScheme.primary` background, `onPrimary` text; shape rounded 18.dp with
  bottom-end 4.dp. Edith: left-aligned, `surfaceVariant`/`onSurfaceVariant`; bottom-start 4.dp.
- Max width 78% of the screen width (`BoxWithConstraints` or `LocalConfiguration`).
- Text selectable (long-press copy) via `SelectionContainer`.
- Basic Markdown for Edith: bold, italics, bullet/numbered lists, inline code, links (tap opens browser).
  Keep it light (a small renderer or a maintained Compose Markdown library — justify the choice).
- Timestamp under each bubble (`bodySmall`, `onSurfaceVariant`), aligned to the bubble's side: "2:06 PM";
  for older days "Mon 2:06 PM" / "Oct 12, 2:06 PM".
- Failed message: Edith-side bubble with an error icon and the error text, plus **Retry** (calls regenerate).
- Cancelled message: normal Edith bubble, muted, with "(stopped)" suffix.
- `toolsUsed` non-empty → a small chip row under the bubble: "Used: weather · web search" (display only).

### 4. In-flight bubble
When `EngineState.Working`: an Edith bubble showing the partial text (streaming) or, before any text, a
small spinner + the status label ("Edith is thinking…", "Checking the weather…"). A **Stop** icon button
on it calls `engine.cancel()` (content description "Stop reply").

### 5. Action row under Edith's bubbles
Exactly as approved: compact row (32.dp buttons, 18.dp icons, minimum-touch-target enforcement off
*for this row only*), pulled up 7.dp onto the bubble edge with a custom `layout` modifier that also
removes the 7.dp from its measured height. Buttons:
- **Play** (`VolumeUp`, "Play reply") — highlighted (filled tonal) while that message is being spoken.
- **Stop** (`Stop`, "Stop playback") — enabled only while that message is speaking.
- **Regenerate** (`Refresh`, "Regenerate reply") — only on the latest Edith message, only when idle.
Play/Stop are wired to a `SpeechController` interface with a no-op implementation until C13.

### 6. Input row
One row: [**Attach** paperclip (C15; hidden until then)] [**Record** dot (C12; hidden until then)]
[`OutlinedTextField` rounded 24.dp, "Message Edith", max 4 lines, IME action Send, trailing **mic** for
dictation (C12; hidden until then)] [**Send** `FilledIconButton`, "Send", enabled when `canSend`].
Use feature flags in the ViewModel state so later chunks just turn their buttons on.

### 7. Accessibility and test hooks
Every icon button has a content description (used by tests and `adb` recipes): "Send", "Stop reply",
"Play reply", "Stop playback", "Regenerate reply", "Conversations", "New chat", later "Attach a file",
"Record audio", "Stop and send recording", "Speak a message".

### 8. Conversations (D6: multiple conversations; storage and engine in C06b)
- **Top app bar** on the chat screen: a **Conversations** icon (opens the list), the current title
  ("New chat" while untitled), and a **New chat** icon (`CurrentConversation.newChat()`). The bottom bar
  is unchanged.
- **List**: a `ModalNavigationDrawer` from the left, newest first (`ChatRepository.conversations()`). Each row
  shows the title, a one-line preview of the last message, and a relative time ("2:06 PM", "Mon",
  "Oct 12"). The open conversation is highlighted, and the one with a reply in progress shows a small spinner.
  Tap → `select(id)` and close the drawer.
- **Rename**: tap the title in the top bar, or use the row's long-press menu → dialog with the current
  title. Saving calls `rename` (an owner rename is never overwritten by auto-title).
- **Delete**: long-press menu → confirm dialog "Delete this chat? This can't be undone." →
  `CurrentConversation.delete(id)` (it stops a reply running there first). Deleting the open chat switches to the next most
  recent one, or a new empty chat if none is left.
- Switching chats keeps a reply running in the old one; coming back shows it still streaming, or finished.
- Each conversation keeps its own unsent draft (`SavedStateHandle`, keyed by conversation id).
- No search and no folders in v1.

## Tests
- ViewModel: maps messages; `isLatestAssistant` only on the newest Edith message; draft survives recreation.
- Compose: sending clears the field and shows the in-flight bubble with the label; Stop calls cancel;
  Regenerate only on the latest Edith message; failed message shows Retry.
- Compose: the action row's bounding box overlaps the bubble's bottom edge by ~7.dp (layout test).
- Screenshot or golden-free visual check in the PR: light and dark theme.
- Conversations: the drawer lists conversations newest first; tapping one shows its messages; New chat on an
  empty chat doesn't create another; rename shows in the top bar and the list; deleting the open chat
  switches as specified; a reply started in A doesn't appear in B, and B's Send is disabled with the hint.

## Acceptance criteria
- [ ] On the emulator: chat with the real model; reply streams; timestamps; auto-scroll; keyboard behaviour correct.
- [ ] Stop mid-reply works; Regenerate works; a failed turn (stop Ollama) shows a clear error + Retry; restart Ollama → Retry succeeds.
- [ ] Rotation mid-reply: the reply keeps streaming after rotation (engine is app-scoped).
- [ ] Two or more conversations on the emulator: switch mid-reply and back; rename; delete; the app
      reopens on the last-used chat after being closed.
- [ ] No crash/ANR in `adb logcat` during the session.

## Pitfalls
- Don't construct anything long-lived in composables. Don't key the auto-scroll only on item count.

## As built (2026-10-31)

Where the code differs from the specification above, the code is right.

**Files** (`chat/ui/`)
- `ChatViewModel.kt`: state and actions.
- `ChatUiState.kt`: the UI model.
- `ChatScreen.kt`: the screen, message list, drawer and dialogs.
- `ChatComponents.kt`: the bubbles, action row and input row.
- `Markdown.kt`: the Markdown renderer.
- Elsewhere: `core/time/MessageTimes.kt` (time formats) and `voice/speech/SpeechController.kt` (the interface,
  plus the silent version used until C13). `AppContainer` gains `speechController` and `chatFeatures`.

**Differences from §1**
- **The draft isn't in `ChatUiState`.** It's Compose state in the ViewModel (`ChatViewModel.draft`), so typing never
  waits on a flow. It's saved per conversation in `SavedStateHandle` (`draft_<id>`) and reloaded when a chat opens.
  `canSend` is the function `state.canSend(draft)`.
- **`error` is a `ChatError`.** It's set only for `PermissionDenied`, the one failure a failed bubble can't fix:
  - the error line offers **Allow**, or **Open settings** after a denial
  - allowing retries the turn
  - every other failure shows only as the failed bubble with **Retry**, so nothing is shown twice
- **Bridging.** The engine reports a step a few milliseconds before Room delivers the stored row, so:
  - the owner's message shows from Send until it's stored (`pendingUserText`)
  - Edith's finished text stays until her stored reply arrives (`InFlight(canStop = false)`)

  Each bridge lasts at most 2 s. Without them, Send and every finished reply flickered.

**Differences from §2–§4**
- **Auto-scroll.** The list is bottom-up (`reverseLayout`), so a growing reply stays in view on its own.
  - Whether to follow new items is decided only when the owner *scrolls*: within about one screen of the bottom.
  - It's never decided when items are added. Deciding on additions was a real bug: Send adds two items at once,
    which looked like "scrolled away".
  - Sending always scrolls to the bottom. Otherwise a **New** button appears.
- **Markdown** is a small renderer of its own (`Markdown.kt`) instead of a library. It has no dependency, and
  unrecognised text stays as written.
  - Blocks are laid out as separate `Text`s, because one string with paragraph styles adds blank lines between
    list items.
  - Links open in the browser through `LinkAnnotation.Url`.
  - The drawer preview uses the same parser, minus the markers.
- **Tools used** shows the raw tool names (`get_weather`); C08 supplies display names.

**Differences from §8**
- The title is clickable to rename it.
- **Back** closes the open drawer. Without that, Back left the app (a regression test proves it).
- Delete calls `CurrentConversation.delete` (C06b).

**Verified**
- `scripts/check.sh --device`: OK (57 device tests), 73 unit tests, lint clean (`build-logs/2026-10-31_23-14-20_check.log`).
- New tests:
  - `ChatViewModelTest` (7):
    - a chat switch
    - a reply in A not shown in B, with B's Send off and the hint
    - per-chat drafts surviving recreation
    - the Regenerate rules
    - a failure shown once, the permission path and allowing
    - Stop and playback
    - delete and rename
  - `ChatScreenTest` (10):
    - send and reply
    - the in-flight label and Stop
    - Stop keeping the partial reply
    - Regenerate only on the newest reply, and the action row overlapping the bubble by 7 dp ±1
    - failure and Retry
    - the drawer: switch, rename, delete, and Back
    - New chat while a reply runs elsewhere
    - auto-scroll on Send from a long chat
    - the New button when scrolled up
  - `ChatFormattingTest` (5, JVM): the Markdown blocks and inline styles, and the times.
- The shared test kit is `androidTest/.../testing/ChatTestKit.kt`. `ChatEngineTest` now uses its fakes.
- **Live on the emulator against the owner's Ollama** (`gemma4:12b-it-q8_0`):
  - replies stream with Markdown, the time and the action row
  - the keyboard pushes the input row up
  - the reply kept streaming through a rotation
  - Stop, Regenerate and Retry all worked
  - New chat while a reply ran elsewhere showed the hint, with Send off, and a spinner in the drawer
  - Edith titled a new chat "Golden Retriever Puppy Names"
  - the app reopened on the last chat after a reinstall
  - light and dark themes both checked
  - no crash in logcat
- **Not done live:** stopping Ollama on the PC to see the failure. A real "Can't reach your Ollama server" failure
  did happen (see L24); its bubble and Retry were checked live.

**Known problem, D13 (owner's decision):** leaving the app (Back or Home) mid-reply kills the reply after about 5 s,
because Android cuts the network of an app in the background (L24). It shows as a server error, and Retry works.
Rotation is fine.

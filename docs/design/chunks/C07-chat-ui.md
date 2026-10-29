# C07 — Chat UI

| | |
|---|---|
| Phase | 1 |
| Depends on | C06 (and C01 navigation) |
| Unblocks | C08 (confirmation card), C12/C13 (input row and action row hooks), C15 |
| Size | Medium–large |
| Read first | 00-product §4.7; 01-architecture §10; 02-standards §2 (content descriptions) |

## Goal

The chat screen the owner approved in v1, rebuilt cleanly: bubbles on opposite sides, streaming reply
with a status label, compact input row, action row hugging Edith's bubbles, timestamps, auto-scroll,
keyboard-aware layout, clear error display.

## Specification

### 1. `ChatViewModel`
Combines `ChatRepository.observe()` and `ChatEngine.state` into
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
"Play reply", "Stop playback", "Regenerate reply", later "Attach a file", "Record audio",
"Stop and send recording", "Speak a message".

## Tests
- ViewModel: maps messages; `isLatestAssistant` only on the newest Edith message; draft survives recreation.
- Compose: sending clears the field and shows the in-flight bubble with the label; Stop calls cancel;
  Regenerate only on the latest Edith message; failed message shows Retry.
- Compose: the action row's bounding box overlaps the bubble's bottom edge by ~7.dp (layout test).
- Screenshot or golden-free visual check in the PR: light and dark theme.

## Acceptance criteria
- [ ] On the emulator: chat with the real model; reply streams; timestamps; auto-scroll; keyboard behaviour correct.
- [ ] Stop mid-reply works; Regenerate works; a failed turn (stop Ollama) shows a clear error + Retry; restart Ollama → Retry succeeds.
- [ ] Rotation mid-reply: the reply keeps streaming after rotation (engine is app-scoped).
- [ ] No crash/ANR in `adb logcat` during the session.

## Pitfalls
- Don't construct anything long-lived in composables. Don't key the auto-scroll only on item count.

# C47 — Keeping photos on iOS

| | |
|---|---|
| Phase | 9 — iOS (Expo) |
| Status | **Built** in Expo Go; see "As built" at the end. The owner's iPhone check is open |
| Depends on | C43 (photo attachments) |
| Ports | C16 §1 and §2 (storage and retention), for the owner's photos |
| Not here | Edith's own files (`<file>` blocks), resending an attachment, audio, "View / Save / Share" file cards (C16 §3–4) |

## Why
C43 held a sent photo in memory only, so after the app restarted the chat said "no longer available" and the model
lost the picture. This keeps the newest photos on the phone, as Android does.

## Specification (differences from Android only)
- **Files:** each sent photo is written to the app's own documents folder as `attachments/<attachment row id>.jpg`
  (written beside the final name and moved into place). A write that fails leaves the photo in memory for this run only;
  the send still works.
- **After a restart** the photos load from disk on demand: when a chat opens, and before a turn builds the model's
  context (the newest 20 pictures of that chat; the context still sends images only for the newest 10 messages).
- **Retention:** Settings → Attachments, "Keep the newest N" (0–50, default **10**), counted across all chats for the
  photos you sent. After a message with photos, and at every app start, photos beyond N are deleted (the row gets
  `removed_at`, so the chat says "no longer available"). Lowering the number asks "This will delete N older attachments."
  first. **0** keeps nothing after the app closes: a photo just sent stays visible until the next start.
- **Usage and delete:** the screen shows "12 photos, 8.4 MB" and **Delete all attachments now** (asks first; chats keep
  their text).
- **Housekeeping:** deleting a chat deletes its photo files; at startup files that no kept row points to are deleted.
- The setting is `keep_recent_attachments` in the key-value store.

## Tests
The store against a fake disk (written, reloaded in a new run, a full disk keeps it for the run); the model gets an
earlier photo after a restart; retention (12 sent, 10 kept, rows marked removed; counted across chats; 0 applied at
startup only; lowering and raising; delete all; orphans; deleting a chat); the setting (default, clamp) and the usage line.

## Acceptance criteria
- [x] `scripts/ios-check.sh` is green.
- [ ] **The owner, on the iPhone:** attach a photo, close Expo Go completely and reopen: the photo is still in the chat,
      and asking about it works. Settings → Attachments shows the count and size; lowering the number asks and removes
      older photos; Delete all works.

## As built (2026-11-20)
Built by an AI agent; Android is untouched.
- Files: `attachments/attachments.ts` (`AttachmentFiles`, the store's `put` / `hydrate` / `forget`),
  `attachments/expoAttachmentFiles.ts`, `attachments/retention.ts`, `ui/AttachmentsSettings.tsx`; changes in
  `data/chatRepository.ts` (`keptAttachments`, `markAttachmentsRemoved`), `chat/engine.ts`, `chat/currentConversation.ts`,
  `settings/settings.ts`, `core/services.tsx`, `ui/ChatScreen.tsx`, `ui/SettingsScreen.tsx`.
- **Not verified:** the disk code (`expo-file-system` writing and reading Base64 on a real iPhone), and whether Expo Go
  keeps the documents folder between sessions of the app.

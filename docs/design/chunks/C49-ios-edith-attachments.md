# C49 — Edith's attachments on iOS: stored pictures from tools, per-direction retention

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave A |
| Status | **Design** |
| Depends on | C47 (photos kept on disk), C46 (home tools), C45 |
| Unblocks | C51 Ring and C52 Blink (snapshots), C56 (Edith's files and resends), C71 (live snapshots in chat) |
| Runs in | Expo Go |
| Size | Medium |
| Ports | C16 §1–2 for the `from_edith` direction, the "tool images" framework change of C25 (`ToolResult.Ok.images`, `AttachmentStore.writeBytes`, `EdithAttachments`), O1 |
| Read first | C47 (the store and retention as built), C15 §3, C16 §1–2 and As built, C25 "Framework changes" in its As built, `06-decisions.md` **O1** |

## Why
So far only the owner's photos are stored (`direction = 'from_user'`). Edith needs to attach pictures **she** produces: a
camera snapshot ("Show me the front door"), later files she writes (C56) and photos she sends back (resend). Both need the
same plumbing: an attachment row with `direction = 'from_edith'`, bytes on disk, the picture drawn in **her** bubble, the
picture offered to a vision model on later turns, and retention counted **per direction** (O1: the 10 newest from the owner and
the 10 newest from Edith, across all chats; resends do not count).

## Specification

### 1. Tools can return pictures
- `ToolResult` becomes `{ ok: boolean; text: string; images?: ToolImage[] }`, `ToolImage { fileName: string; mimeType: 'image/jpeg'; base64: string }`; `ok(text, images?)` helper keeps the old call sites valid.
- `runToolTurn` collects the images of every tool call in the turn (`TurnOutput.images`) and:
  - puts them in the `role:'tool'` message's `images` **only if the model has `vision`** (otherwise the text says "A picture was shown to the owner; you can't see it");
  - never stores them in the tool exchange (L3): they leave the turn only through `TurnOutput.images`.
- At most 4 pictures per turn (the rest are dropped with a line in the tool text), each ≤ 1 MB after JPEG re-encode at the C43 settings (longest side 1536, q 0.85): use the existing `ImageProcessor`/`prepareImage` path; a tool that already returns a JPEG passes `toJpeg` only when it is larger than the limit.

### 2. The engine stores them as Edith's attachments
- After `addAssistantMessage` the engine calls `repo.addEdithAttachments(messageId, images)` (one transaction): rows with `direction = 'from_edith'`, `kind = 'image'`, `number = COALESCE(MAX(number),0)+1` over **all** attachments (the numbering is shared with the owner's; labels use it), `file_name`, `mime_type`, `size_bytes`, `created_at`.
- The bytes go to `AttachmentStore.put(rowId, base64)` (C47: memory + `attachments/<id>.jpg`).
- `ChatRepository.keptAttachments(direction)` already takes a direction (C47). `markAttachmentsRemoved`, `beyondKeep`, `applyRetention`, `deleteAllAttachments`, `removeOrphans` become **direction-aware**: `applyRetention(deps, keep, atStartup)` runs for `'from_user'` and `'from_edith'` separately with the same `keepRecentAttachments` number; `removeOrphans` keeps files of either direction's kept rows; resends (`resend_of IS NOT NULL`) are excluded from both counts (C56 creates them).
- A failed turn or a Regenerate that deletes the assistant message deletes its Edith attachment rows (cascade by `message_id`) **and their files** (`AttachmentStore.forget`): extend `ChatRepository.deleteMessage` to return the attachment ids, or have the engine read them first, exactly as `CurrentConversation.delete` does for chats (C47).
- Deleting a conversation already forgets its attachment files (C47); verify it includes `from_edith` rows.

### 3. Context
`contextBuilder` already writes labels and images for the owner's messages. Extend it to **assistant messages with attachments**:
- Label line `[Attachment 7: front-door.jpg (image)]` appended to the assistant text (so the model can refer to it and C56 resends it by number).
- Images included in the media window (last 10 messages) only for vision models, only while the file is kept; otherwise the label plus " — no longer available".
- The prompt section `attachmentsSection` also says: "Pictures you showed the user appear as labels in your own messages." (only for models with `vision` or always; keep the text short.)

### 4. Display
`MessageBubble` for an assistant message shows the stored pictures above the text, as for the owner: thumbnails (160 pt) that open in `ImageViewer`; "name — no longer available" when removed (the `BubbleAttachments` component is reused; `ChatScreen.bubbleAttachments` already filters by `messageId`; remove its `direction === 'from_user'` filter and make the direction-specific parts explicit).
While the reply streams the in-flight bubble shows the pictures as soon as the tool returns them (optional polish: a spinner "Getting the picture…" is the tool's status label).

### 5. The first real consumer: `camera_snapshot`
Define the tool here so Ring/Blink only supply `HomeBackend.snapshot`:
- `camera_snapshot(device)` — "Shows a current picture from a camera or doorbell". Resolves the device with `matchDevice` over devices with capability `'snapshot'`; ambiguous → candidates; none → names. `enabled()` is true while any visible device has `'snapshot'`.
- Calls `backend.snapshot(id, signal)` within `snapshotTimeoutMs ?? 20_000`; errors become text (offline, no picture in time, motion detection off) — the service chunk supplies the words.
- Returns `ok("Showing <name>, taken <age>.", [image])`. No confirmation (read-only).
- Added to `home/homeTools.ts`, registered with the other two home tools. The Demo home gets a **demo doorbell camera** (`DemoBackend.snapshot` returns a small generated JPEG, a base64 constant of a 160×120 gradient, ≤ 3 KB, committed as a string) so the whole path can be tried without a service.

## Tests
- Runner: images pass to the next round only for a vision model; a non-vision model gets the explanatory text; more than 4 pictures are cut; images never appear in stored messages.
- Engine on SQLite: a turn whose tool returns a picture stores a `from_edith` row numbered after the owner's, writes the file, sends labels with the next request, and shows `no longer available` after retention; Regenerate removes the row and the file; deleting the chat removes files.
- Retention per direction: 12 from the owner and 15 from Edith with N = 10 → 2 and 5 removed; resends not counted (insert a row with `resend_of`); lowering N prunes both.
- `camera_snapshot`: found, ambiguous, none, the backend failing, the timeout, no cameras → tool not offered.
- UI: an assistant bubble with a picture opens the viewer; with a removed one shows "no longer available".
- Context: assistant label lines, media window, vision gate.

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green.
- [ ] **Owner, on the iPhone** (Demo home on): "Show me the doorbell camera" → a picture appears in Edith's bubble; tap → full screen; a vision model can then describe it ("what colour is the picture?"); after more than 10 pictures the oldest says "no longer available"; Settings → Attachments' count includes both directions (update `AttachmentsSettings` text: "photos and pictures", counts per direction).

## Risks and unverified assumptions
- Memory: 4 pictures × 1 MB Base64 in memory per turn is fine; the media window re-sends at most 10 messages' pictures. Keep `AttachmentStore.hydrate` limited to the newest 20.
- Models differ in how they handle an image in a `tool` message. If a vision model ignores it, fall back to adding the image to the next **user-role** message of the same turn (document which works).

## Not in this chunk
`<file>` blocks and resends (C56); audio; any service's snapshot implementation (C51, C52).

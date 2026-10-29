# Edith rebuild — design documents

This folder is the complete design for **Edith v2.0**, a from-scratch rebuild of Edith, a personal-assistant
Android app powered by the owner's local Ollama server. It is written so that **different AI models, working in
different applications, can each implement one chunk independently** and hand the result back as a pull request.

## How to use this (for an implementing AI)

1. Read the **shared context** in order. It's long on purpose; it encodes decisions the owner already made and
   mistakes v1 already made.
   1. [`00-product.md`](00-product.md) — what Edith is, v1 scope, the owner's binding decisions, Edith's persona.
   2. [`01-architecture.md`](01-architecture.md) — stack, packages, DI, data flow, database schema, settings keys, errors, security.
   3. [`02-standards.md`](02-standards.md) — definition of done, code and test standards, owner preferences, git/PR rules.
   4. [`03-ollama-reference.md`](03-ollama-reference.md) — **verified** Ollama API behaviour with real JSON (tool calling, streaming, audio).
   5. [`04-lessons-learned.md`](04-lessons-learned.md) — what went wrong in v1 and the rule each lesson produced.
   6. [`05-environment.md`](05-environment.md) — the owner's machine, emulator runbook, `adb` recipes, Windows gotchas.
   7. [`06-decisions.md`](06-decisions.md) — decided items and **open questions you must ask the owner** before building on them.
2. Take **one chunk** whose dependencies are merged. Read its file completely.
3. Before writing code: check `git status` (other agents may have left work — don't discard it), pull `master`,
   branch `cNN-short-name`, and ask the owner any open decision your chunk lists.
4. Implement, test, verify on the emulator, update docs, open a PR (`02-standards.md` §1, §5–6).
5. Mark the chunk's status in the table below in the same PR.

If something in a chunk is wrong or impossible, don't silently diverge: explain it in the PR and update the
design in the same PR, or ask the owner.

## Chunks

| ID | Chunk | Phase | Depends on | Status |
|---|---|---|---|---|
| [C01](chunks/C01-project-reset-and-skeleton.md) | Project reset and skeleton | 1 Foundation + chat | — | Done (C01 PR) — **start at C02** |
| [C02](chunks/C02-core-infrastructure.md) | Core infrastructure (DI, errors, logging, crash reports, secrets) | 1 | C01 | Not started |
| [C03](chunks/C03-ollama-client.md) | Ollama client (streaming, tool-call DTOs, capabilities) | 1 | C02 | Not started |
| [C04](chunks/C04-settings-and-model-picker.md) | Settings store, server setup, model picker | 1 | C02, C03 | Not started |
| [C05](chunks/C05-conversation-storage.md) | Conversation storage (Room, full v1 schema) | 1 | C02 | Not started |
| [C06](chunks/C06-chat-engine.md) | Chat engine (prompt, context, streaming, cancel, regenerate) | 1 | C03, C04, C05 | Not started |
| [C07](chunks/C07-chat-ui.md) | Chat UI (bubbles, action row, input row, timestamps) | 1 | C06 | Not started |
| [C08](chunks/C08-tool-framework.md) | **Tool framework and tool loop** (+ `get_current_time`) | 2 Tools | C03, C06, C07 | Not started |
| [C09](chunks/C09-weather-tool.md) | Weather tool (Open-Meteo) | 2 | C08 | Not started |
| [C10](chunks/C10-web-search-and-reading.md) | Web search and web page reading | 2 | C08 | Not started |
| [C11](chunks/C11-device-location.md) | Device location ("here") | 2 | C08, C09 | Not started |
| [C12](chunks/C12-voice-input.md) | Voice memos and dictation | 3 Voice | C03, C06, C07 | Not started |
| [C13](chunks/C13-spoken-replies.md) | Spoken replies (phone voice, streaming sentences) | 3 | C06, C07 | Not started |
| [C14](chunks/C14-hands-free.md) | Hands-free mode (wake word, follow-ups) | 3 | C12, C13, C06 | Not started |
| [C15](chunks/C15-attachment-intake.md) | Attachments in (photos, audio files) | 4 Attachments | C05, C06, C07 | Not started |
| [C16](chunks/C16-attachment-storage-and-edith-files.md) | Storage, retention (default 10), Edith's files, resends | 4 | C15, C05, C07, C13 | Not started |
| [C17](chunks/C17-google-account.md) | Google account sign-in and authorization | 5 Google | C02, C04, C08 | Not started |
| [C18](chunks/C18-google-calendar.md) | Google Calendar tools and Calendar tab | 5 | C17, C08 | Not started |
| [C19](chunks/C19-gmail.md) | Gmail search, read, draft | 5 | C17, C08 | Not started |
| [C20](chunks/C20-smart-home-architecture.md) | Smart home architecture (**v2, design only**) | 6 Deferred | C08, C17 | Design |
| [C21](chunks/C21-smart-home-service-notes.md) | Smart home service notes (**v2 research**) | 6 Deferred | — | Research |

### State at handoff (2026-10-29)
- `master` has the design and the C01 skeleton with its build/deploy tooling (`05-environment.md` §2, §6). Tag
  `edith-v1` = the old app. Branch `rebuild-design` is an already-merged leftover — ignore it, don't build on it.
- **Next: C02.** Then C03 and C05 in parallel.
- Owner decisions still open are in `06-decisions.md`. Ask early about the ones your chunk lists; the first ones
  needed are **D8** (C03), **D6** (C07), **D11** (C08), **D7** (C09), **D3** (C10), **D4** (C14), **O1** (C16),
  **D9** (C17), **D5** (C19).
- No GitHub Release has been published yet; `scripts/publish.sh` is untested until the owner asks for the first one.

### Parallelism
After C01–C02: C03 and C05 can run in parallel. After C06–C07: C08, C12, C13 and C15 can run in parallel
(they touch different packages; coordinate on `AppContainer` and the input row). C09, C10, C11 can run in
parallel after C08. C17 can start any time after C08; C18 and C19 in parallel after C17.

## Glossary

| Term | Meaning |
|---|---|
| Owner | The one person Edith is built for; makes all product decisions |
| Ollama | Local LLM server on the owner's PC (`http://<pc>:11434`) |
| Turn | One user message and Edith's complete reply, including any tool rounds |
| Tool round | One model call that returns tool calls, plus executing them |
| Tool exchange | The assistant tool-call messages and `role:"tool"` results of a turn — never persisted |
| Capability | What a model can do per `/api/show`: `completion`, `tools`, `vision`, `audio`, `thinking` |
| Media window | The last 10 messages, whose images/audio are re-sent to the model |
| Integration | An external account Edith connects to (Google now; smart home in v2) |
| Jarvis | The owner's separate Electron desktop assistant (`D:\Dev Projects\jarvis`), a useful reference |
| v1 | The previous Edith, tag `edith-v1`; abandoned; no code reused |

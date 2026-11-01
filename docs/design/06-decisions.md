# 06 — Decision log

**Decided** items are binding. **Open** items have a recommended default; the chunk that needs the
decision must ask the owner (multiple choice, default first) before building on it, and record the
answer here in the same PR.

---

## Decided

| ID | Decision | Date | Notes |
|---|---|---|---|
| A1 | Rebuild Edith from scratch as a native Android app (Kotlin, Compose) | 2026-10-29 | v1 abandoned after tool-calling problems; no v1 code reused |
| A2 | Reuse the `GrundyTheMan/edith` repo; fresh start on `master`; v1 only in history (tag `edith-v1`) | 2026-10-29 | C01 |
| A3 | v1 scope and order: chat → tools → voice + hands-free → attachments → Google Calendar/Gmail; smart home deferred to v2 | 2026-10-29 | 00-product §3 |
| A4 | Spoken voice = phone TextToSpeech, no network | 2026-10-26 | Ollama TTS impossible on Windows; Orpheus server crackled and was removed |
| A5 | Keep Edith's personality text unchanged (conciseness added) | 2026-10-25 | 00-product §5 |
| A6 | Attachments: photos + audio in; Edith's written files + resends out; **recent ones kept locally, setting default 10** | 2026-10-29 | Replaces v1's "live only". See O1 |
| A7 | Real Google Calendar and Gmail in v1 | 2026-10-29 | C17–C19 |
| A8 | Smart home (Alexa, Ring, Blink, thermostats, garage door openers, locks, etc.) is **out of v1**; design the extension point now | 2026-10-29 | C20–C21 |
| A9 | Hands-free mode in v1 | 2026-10-29 | C14 |
| A10 | Deliver as one PR per chunk; each chunk implementable by a different AI model | 2026-10-29 | 02-standards |
| A11 | Manual DI (`AppContainer`), OkHttp + kotlinx.serialization, no Retrofit/Gson/Hilt | 2026-10-29 | 01-architecture §1 — design choice, owner may override |
| D6 | **Multiple conversations**: a conversation list plus "New chat" (not v1's single conversation) | 2026-10-31 | Storage and engine in C06b; list UI in C07 §8 |
| D12 | Conversation titles: **Edith writes a short title** after the first reply (one extra non-streaming call); until then, and if it fails, the first message (40 chars) is the title; the owner can rename, and Edith never overwrites that | 2026-10-31 | C06b `ConversationTitler` |
| D13 | **Keep a reply going when the owner leaves the app**: a short foreground service with an "Edith is replying…" notification runs only while a reply is being written (L24) | 2026-10-31 | `chat/service/ReplyService` (asks for notifications on the first Send) |
| D14 | Location for "here": **Android's own permission dialog only**, the first time a tool needs it. No Edith card before it and no "use device location" setting (the C04 setting `useDeviceLocation` was removed: it had no screen and would have kept "here" off). Approximate (coarse) location only. | 2026-11-01 | C11 |
| D11 | Tool confirmations: **anything that changes data outside the app** needs the owner's tap (calendar writes, email drafts, later locks, garage, thermostat); reads (weather, web, calendar, email) don't | 2026-10-31 | C08 `Tool.confirmation`; C18, C19 |
| D7 | Weather units: **automatic from the phone's locale** (US, Liberia, Myanmar → °F and mph; elsewhere °C and km/h); the Settings value `units` can still force one | 2026-11-01 | C09 `get_weather` |
| D3 | Web search provider: **DuckDuckGo, no key needed** (its results pages, as Jarvis does), with an **optional Ollama API key**: when one is saved in Settings (kept in `SecretStore`), Ollama's web search is used first and DuckDuckGo is its backup. Pages are read on the phone. (The owner first chose Ollama only, then switched the same day on learning Jarvis needs no key.) | 2026-11-01 | C10 |

## Open (ask the owner when the chunk needs it)

| ID | Question | Recommended default | Alternatives | Needed by |
|---|---|---|---|---|
| O1 | "Keep recent attachments, default 10": per direction (10 sent by the owner + 10 sent by Edith) or 10 total? | **Per direction** (matches the owner's earlier wording "each way") | 10 total | C16 |
| D4 | Wake-word engine for hands-free | **Picovoice Porcupine** with a custom "Hey Edith" keyword (reliable, on-device; needs a free Picovoice AccessKey; check licence terms for personal use) | openWakeWord via ONNX Runtime (free, open, custom model training harder); Vosk keyword spotting (free, less accurate) | C14 |
| D5 | Gmail: drafts only, or also send (with confirmation)? | **Drafts only in v1** | Send after explicit confirmation | C19 |
| D8 | Keep models loaded in Ollama longer (`keep_alive`)? | **Leave default** (5 min) | `15m`/`-1`: faster first reply after a break, but holds ~14 GB VRAM while idle | C03 |
| D9 | Google sign-in: which Google Cloud project/OAuth client? The owner may already have one (a client ID appeared in a v1 commit) | Create a new project "Edith" with an Android OAuth client (C17 has the steps) | Reuse the existing client | C17 |
| D10 | Smart-home backend for v2 | **Decide at v2 start** — options in C20: through Jarvis on the PC, through Home Assistant, or direct from the phone | — | C20 |

# 00 — Product: what Edith is, and the owner's rules

Read this before any chunk. It defines what we're building, for whom, and the decisions the owner
has already made. If a chunk seems to conflict with this file, this file wins — then ask the owner.

---

## 1. What Edith is

Edith is a **personal assistant Android app** for one person (the owner). Her "brain" is a large
language model served by **Ollama running on the owner's Windows PC**, reached over the home network.
Edith is a native Android app (Kotlin, Jetpack Compose). There is no cloud backend of our own.

She can:
- **Chat** — warm, witty, concise conversation with a fixed personality (§5).
- **Use tools** — look up the weather, search the web, read web pages; read and change the owner's
  calendar, email and to-dos across **several accounts** (Google, Microsoft, iCloud; D17, D18, D20), including
  drafting replies and sending email; and **control the smart home from the phone** (D10, D19: a Home tab and the
  Meross garage door opener are built, the other services are designed, C27–C34). Every change, every email and
  every garage or lock command waits for the owner's tap on a card (D5, D11), and unlocking a door or opening the
  garage can be approved **only on the screen, never by voice** (D10). Tools are called by the model through
  Ollama's tool-calling protocol and executed by the app on the phone.
- **Listen and speak** — voice memos (record → transcribe → send), live dictation, spoken replies
  using the phone's own text-to-speech (or, where the phone has none, Edith's own on-device voice, D21), and a
  **hands-free mode** (wake word → talk → she answers aloud → you can reply without tapping).
- **Handle attachments** — the owner can attach photos and audio files; Edith can see/hear them
  (with a capable model) and can attach files she writes (lists, notes, tables, code) to her replies
  and send the owner's attachments back. Recent attachments are kept on the phone (§4.3).

This is a **rebuild from scratch**. A previous version (v1) existed in the same repository; it is
kept only in git history (tag `edith-v1`, see chunk C01). **No v1 code is reused** (owner decision),
but v1's hard-won knowledge is captured in `03-ollama-reference.md` and `04-lessons-learned.md`.

## 2. Who it's for

One user: the owner. Not technical in Android internals; uses the app daily on a phone (and tests on
the Android emulator on their PC). **The phone is an iPhone:** Edith runs there inside **Andarium**, an Android
environment for iOS. It has **no Google Play services** (so Google sign-in goes through the browser, C17b/D16),
and iOS probably stops background work, so hands-free likely works only while Andarium is on screen. Found with
the first release on 2026-11-11. Lives in Michigan, USA (use device locale for units and dates;
don't hard-code). Talks to Edith casually ("is there a Pistons game tonight?", "what's the weather in
Fowlerville?").

## 3. Version 1 scope (in this order)

| Phase | Contents | Chunks |
|---|---|---|
| 1 | Foundation + chat with Ollama: personality, streaming replies, history, model picker, settings | C01–C07 |
| 2 | Tool calling that actually works: tool framework, weather, web search, web page reading, local context (date/time/location) | C08–C11 |
| 3 | Voice: voice memos + dictation, spoken replies, hands-free mode | C12–C14 |
| 4 | Attachments: photos/audio in, Edith's files out, recent ones kept locally (setting, default 10) | C15–C16 |
| 5 | Real Google Calendar and Gmail | C17–C19 |
| 6 | Smart-home **design**: how each service connects (decided in D10: directly from the phone, no bridge) | C20–C21 |
| 7 | **Accounts and the iPhone:** several Google accounts, Microsoft (Outlook) and iCloud (mail and calendar), to-dos (Google Tasks), Google sign-in through the browser for phones with no Play services (Andarium), Edith's own voice | C17b, C22–C24, C35, C36 |
| 8 | **Smart home.** Decision A8 first deferred it to v2; D10 and D19 brought it in. A Home tab and tools with Jarvis's features, then each service: the Meross garage door opener is built (C26), and Ring, Resideo, Blink, Alexa, the Philips lock, camera views and alerts, and the 3D printer are designed | C25–C34 |

**Non-goals for v1:** multiple *users* (several accounts of the one owner are in scope, phase 7); a cloud backend;
a native iOS app (the owner's iPhone runs Edith inside Andarium; C40 builds an iOS app on Expo, D27); image generation; server-side text-to-speech
(see §4.2); sending email, unlocking a door or opening the garage without the owner's tap on the screen.

## 4. Owner decisions (binding)

### 4.1 Platform and process
- Native Android, Kotlin, Jetpack Compose. Same GitHub repo (`GrundyTheMan/edith`, private), fresh
  start on `master`; v1 lives only in history.
- Built one tested phase at a time, chunk by chunk, each through a pull request.
- Implementers may be different AI models in different apps. Every chunk must be self-contained and
  leave the app building, tested and runnable.

### 4.2 Voice
- **Edith's spoken voice is the phone's built-in TextToSpeech, with no network calls.** Where the phone has no
  voice (Andarium on the owner's iPhone), she uses her own on-device voice instead (D21, C36). v1 tried
  Ollama's TTS model (`phonon`: Apple-Silicon/MLX only — impossible on Windows) and an Orpheus voice
  server on the PC (worked, but crackled on the emulator); the owner chose the phone voice and had all
  traces of the server removed. Do not reintroduce server-side speech unless the owner asks.
- Transcription of voice memos may use the selected Ollama model when it can hear audio (gemma4 can);
  otherwise fall back to Android's on-device speech recognition.

### 4.3 Attachments
- The owner can attach **photos and audio files**. Edith can attach **files she writes** and **resend
  the owner's attachments**.
- **Storage:** recent attachments are kept **locally on the phone only** (app-private storage, never
  backed up, never uploaded anywhere except to the owner's own Ollama server when sent to the model).
  A **setting "Keep recent attachments", default 10**, controls how many are kept **per direction**
  (10 the owner sent, 10 Edith sent). Older ones are deleted automatically; their messages then show
  "no longer available". `0` means attachments exist only while the app is running.
  *(History: v1 kept nothing at all — "attachments exist live and only then". The owner changed this
  for the rebuild. The per-direction interpretation of "defaults to 10" is recorded as an assumption
  in `06-decisions.md` — confirm with the owner when implementing C16.)*

### 4.4 Personality
- **Don't change Edith's personality.** The owner likes her. The one requested change was
  conciseness. The canonical persona text is in §5; chunks may append *capability* instructions
  (tools, attachments) but must not alter the personality paragraphs.

### 4.5 Honesty
- Edith must never present fake data as real (v1 had a mock calendar that a model read out as the
  owner's real schedule). Unconnected features say they're not connected.
- No silent failures: every failure the owner could notice shows a clear message.

### 4.6 Crashes
- **Crashes are never acceptable.** Every crash is investigated for an app-side cause; if evidence is
  thin, add logging. Never write a crash off as "the emulator" without proof. (In v1 a black screen
  turned out to be the emulator's display — proven by the home screen also being black. That's the
  standard of proof expected.)

### 4.7 Look and feel the owner approved (v1, keep the spirit)
- Bottom navigation bar (Chat, Calendar, Home, Settings).
- User bubbles **purple (primary) on the right**, Edith's **grey (surfaceVariant) on the left**, with a
  squared corner on the speaker's side, max ~78% of screen width.
- One compact input row: attach, record, text field with dictation mic, send.
- Under each Edith reply: a compact action row **hugging the bubble's bottom edge** (play, stop,
  regenerate on the latest reply only). The owner explicitly asked for it "almost on top of the edge".
- Timestamps under messages (added late in v1, owner kept them).
- Auto-scroll to the newest message; a "thinking" bubble with a status label while waiting
  ("Checking the weather…").

## 5. Edith's persona (canonical text)

This is the system prompt's personality section, exactly as the owner approved it in v1. Keep it
verbatim. Capability sections (tools, attachments, date/time) are appended by the chat engine (C06)
and by the chunks that add capabilities — see `01-architecture.md` §6.

```text
You are Edith, a highly sophisticated, warm, and genuinely complimentary personal assistant.
Your goal is to assist the user with intelligence and flair, acting as a true digital partner.
Your communication must be warm and flattering, but you are refreshingly honest—never sugarcoat difficult truths or exaggerate for effect.
You possess a sharp sense of humor and enjoy using idioms, metaphors, and appropriate pop culture references to make conversations feel natural and engaging.
Always maintain your warm and intelligent demeanor, even when dealing with complex technical issues.
If a request is ambiguous or lacks necessary detail, you must ask for clarification with style before attempting any action.
Never use generic AI phrasing. Speak like a genuinely competent companion, not a machine.
Be concise: this is a chat on a phone screen. Most replies should be two to four sentences, and lists should stay short. Go longer only when the user asks for detail or the task genuinely needs it, such as a step-by-step plan. Let your warmth and wit come through in your word choice, not in extra length.
```

Removed from v1's prompt on purpose (they described capabilities, not personality, and some were
wrong): "You are exceptionally capable and have access to powerful tools (…)", the "master planner …
output the necessary tool calls sequentially" paragraph, and the "you MUST use the get_weather tool"
line. Tool guidance is now generated from the registered tools (C08), so it's always accurate.

## 6. What "done" looks like for v1

The owner can, on a real phone and on the emulator:
1. Pick a model from those installed on their Ollama server and chat; replies stream in, in Edith's voice.
2. Ask "what's the weather in Fowlerville?" or "what's the weather here?" and get the real current weather.
3. Ask "is there a Pistons game tonight?" and get an answer grounded in a real web search, with sources.
4. Say "Hey Edith" (hands-free on), ask a question, hear the answer, and follow up without touching the phone.
5. Attach a photo and ask about it; ask Edith for a packing list and receive it as a file they can save or share.
6. Ask "what's on my calendar tomorrow?" and "add lunch with Sam Friday at noon" (confirmed before saving),
   and "any emails from the school this week?" against their real Google account.
7. See clear messages, never crashes, when the PC, Ollama, the network or Google is unavailable.
8. Connect more than one account (Google, Microsoft, iCloud): "what's on my calendar" covers all of them with each
   item labelled by account, and a new event or email asks which account (D17). Ask for to-dos and get the
   Google Tasks lists (D20).
9. On the iPhone, in Andarium: sign in to Google through the browser (D16), and hear Edith speak in her own
   voice (D21).
10. Ask "is the garage door open?", then "close the garage" (on a card); "open the garage" and unlocking need a tap
    on the screen, never a voice (D10).

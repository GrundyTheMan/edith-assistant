# C13 — Spoken replies (phone voice)

| | |
|---|---|
| Phase | 3 |
| Depends on | C06, C07 |
| Unblocks | C14 |
| Size | Medium |
| Read first | 06-decisions A4 (phone TTS only, no network); 04-lessons L13–L15 |

## Goal

Edith reads replies aloud with the phone's built-in TextToSpeech — starting as soon as the first sentence
has streamed in — with per-message play/stop and a Settings switch. **No network calls for speech.**

## Specification

### 1. `SpeechOutput` (`voice/speech/`)
```kotlin
interface SpeechController {                      // the interface C07's action row already uses
    val speakingMessageId: StateFlow<Long?>       // drives the Play button highlight
    fun play(messageId: Long, text: String)
    fun stop()
}
class SpeechOutput(context, settings, clock) : SpeechController, ReplyListener
```
- Wraps `android.speech.tts.TextToSpeech` (init once, app-scoped; language from device locale, fallback US
  English; speech rate from settings). Tracks utterances with ids `"<messageId>-<n>"` and an
  `UtteranceProgressListener`; `speakingMessageId` clears when the last utterance of that message finishes,
  errors or is stopped — ignore callbacks from stopped utterances (compare ids).
- **Streaming speech** (`ReplyListener` from C06): `onDelta` feeds a `SentenceChunker`; each completed
  sentence is queued with `QUEUE_ADD`. `onFinished` flushes the remainder. `onCancelled` stops. Only when
  `speak_replies` is on.
- `SentenceChunker`: split on `.`, `!`, `?`, `…` followed by space/newline (not inside numbers like 3.5, not
  after common abbreviations: Mr. Mrs. Dr. St. vs. e.g. i.e. a.m. p.m.); a sentence longer than ~100 chars is
  cut at the first comma/semicolon/colon/dash after ≥25 chars (L15). Minimum chunk 2 words.
- `cleanForSpeech`: remove Markdown (`**`, `#`, backticks, `>`), link syntax → link text, emoji (`\p{So}` and
  `️`), attachment/file tags and their content (C16 conventions), URLs → "a link". Collapse whitespace.
- Stop speaking when: the owner sends a message, starts recording or dictating, starts hands-free listening,
  or taps Stop. Audio focus: request `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` while speaking; stop on focus loss.
- TTS not ready / no voice data → log + a one-time notice in Settings ("Speech isn't available — install a voice in Android settings").

### 2. Settings (Voice section)
"Speak Edith's replies" switch ("Reads each new reply aloud with the phone's voice"), speech-rate slider
(0.6–1.6), **Test voice** button ("Hi, I'm Edith."), link to Android TTS settings.

## Tests
- SentenceChunker: abbreviations, decimals, ellipses, long-sentence cut, trailing remainder.
- cleanForSpeech: markdown, emoji, links, file tags removed.
- SpeechOutput with a fake TTS engine wrapper: streaming deltas produce utterances in order; stop clears state;
  a late `onDone` for a stopped utterance doesn't clear a newer one; speak_replies off → no auto speech but Play works.

## Acceptance criteria
- [x] On the emulator: speech starts within ~1 s of the first sentence arriving (measure: log timestamps of
      first delta vs first utterance start; budget < 1.5 s after first sentence completes).
- [x] Play/Stop/highlight behave per C07; switching off auto-speech works; Test voice works.
- [x] Network: no requests for speech (see As built: the voice is an offline one; Edith's speech code makes none).

## As built (2026-11-01)

Where the code differs from the specification above, the code is right.

**Files** (`voice/speech/`)
- `SpeechOutput.kt`: the voice. It is the app's `SpeechController` (Play/Stop) and a `ReplyListener` (streaming replies).
- `SpeechText.kt`: `SentenceChunker` (written without regexes, L25) and `SpeechText.clean`.
- `AndroidTts.kt`: `TtsEngine` (the interface tests fake) and `AndroidTts` (Android's `TextToSpeech`, audio focus).

Also:
- `ui/settings/VoiceSettings.kt`: Settings → Voice.
- `AppContainer.speechOutput`, and `speechController` now returns it.
- Manifest: a `<queries>` entry for `android.intent.action.TTS_SERVICE`. Without it Android 11+ hides the engines.
- `ReplyListener` gained `onReset` (text before a tool call was dropped) and `onFailed`, and `onFinished` now gets the
  stored message's id.

**Differences from the spec**
- **Utterance ids** are `"<generation>-<n>"`, not `"<messageId>-<n>"`: a streaming reply has no message id until it's
  stored. Each Play, Stop and new reply starts a generation, and callbacks from older ones are ignored.
  - While a reply streams, `speakingMessageId` is `SpeechOutput.LIVE_REPLY` (-1). When it's stored and still being
    spoken, it becomes the message's id, so its Play button lights up and Stop works.
- **Stopping during a streaming reply** keeps the rest of that reply quiet; the next reply speaks again. Playing an
  older message while a reply streams does the same.
- **Text before a tool call** ("Let me check…"): sentences already complete are spoken; an unfinished one is dropped
  with the text.
- **Sentences:**
  - A line break also ends a sentence, so list items are spoken one by one.
  - A one-word piece ("Sure.", "1.") is joined to the next sentence (the spec's 2-word minimum).
  - Edith's files (`<file>` blocks, C16) are skipped as they stream, even when a tag is split across deltas.
- **Cleaning** also removes bullets and `_emphasis_`, and keeps a sentence's full stop after an address ("a link.").
- **Voice choice:** the phone's language, else US English. If the default voice needs the network, an installed one
  of the same language is used instead (A4).
- **Turning "Speak Edith's replies" off** also stops what's being spoken. Play still works.
- **Not available** (no engine, no voice) → the reason shows in Settings → Voice and Test voice is off. It's
  not a one-time notice: it stays while there's no voice.
- **Speed:** a slider in steps of 0.1 from 0.6 to 1.6, saved when let go.

**Verified**
- `scripts/check.sh --device`: OK. 134 unit tests (16 new in `SpeechOutputTest`), lint clean, 76 device tests
  (3 new: `SpeechTextDeviceTest` ×2, `VoiceSettingsTest`). Log: `build-logs/2026-11-01_12-09-34_check.log` (rerun after the offline-voice change).
- `SpeechOutputTest` (JVM) covers:
  - the chunker: deltas, abbreviations, decimals, times, ellipses, the long-sentence cut, commas in numbers,
    one-word pieces, lines, the remainder, and file blocks
  - cleaning
  - `SpeechOutput` with a fake engine: order and speed, the switch to the message id, Stop and late callbacks, auto
    speech off with Play still working, Stop mid-reply, cancelled and failed replies, the dropped pre-tool text,
    lost audio focus, and the speed range
- `SpeechTextDeviceTest` runs the cleaning regexes and the chunker on Android's regex engine (L25).
- **Live on the emulator** (`gemma4:12b`, Google's text-to-speech, voice `en-US-language`, network: false):
  - **Speech started 218, 53 and 60 ms after the first sentence was ready** (600, 972 and 915 ms after the reply's
    first text). Budget: 1.5 s.
  - `dumpsys audio` shows Google TTS players (`USAGE_ASSISTANT`, `CONTENT_TYPE_SPEECH`) for each sentence.
  - **Play** on a stored reply → Play highlighted, Stop enabled; Stop → playback ended, audio focus released.
  - **Weather (with a tool call)** → spoken.
  - **Stop reply mid-stream** → speech stopped at once, focus released.
  - **Switch off** → a new reply made no TTS player; back on → spoken again.
  - **Test voice** → spoken.
  - No crash; no errors logged.
  - **Network:** Edith's speech code makes no requests, and the voice reports it needs no network.
- **Not verified:**
  - **Hearing it.** On the emulator I can see playback start and stop, not judge how it sounds: pauses between
    sentences, pronunciation, and the speed slider's effect.
  - **Losing audio focus to another app** (a call, a navigation prompt): unit-tested only.
  - **A phone without a voice installed** (the Settings message): only the UI is tested.

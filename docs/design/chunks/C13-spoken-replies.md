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
- [ ] On the emulator: speech starts within ~1 s of the first sentence arriving (measure: log timestamps of
      first delta vs first utterance start; budget < 1.5 s after first sentence completes).
- [ ] Play/Stop/highlight behave per C07; switching off auto-speech works; Test voice works.
- [ ] `adb` network check: no network requests during speech (Ollama server log shows only chat calls).

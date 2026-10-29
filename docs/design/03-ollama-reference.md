# 03 — Ollama reference (verified behaviour)

Everything here was observed against the owner's Ollama server (**v0.40.13**, Windows, RTX 5080) on
2026-10-29 unless marked otherwise. JSON samples are real responses (trimmed where noted). Copy them
into `app/src/test/resources/ollama/` as test fixtures.

Base URL from the phone/emulator: the PC's LAN address, e.g. `http://192.168.4.82:11434` (what the
owner's app uses), or `http://10.0.2.2:11434` from the Android emulator. Ollama has **no authentication**.

---

## 1. Installed models and capabilities (as of 2026-10-29 — re-check with §6; the owner changes models)

| Model | Capabilities (`/api/show`) | Max context | Notes |
|---|---|---|---|
| `gemma4:12b-it-q8_0` | completion, vision, audio, tools, thinking | 262144 | Best quality; owner's v1 default family |
| `gemma4:e4b-it-q8_0` | completion, vision, audio, tools, thinking | 131072 | Faster; the owner's current app setting |
| `qwen3.5:9b-q8_0` | completion, vision, tools, thinking | 262144 | No audio |
| `ornith:9b` | completion, tools, thinking | 262144 | No vision/audio |
| `gpt-oss:20b` | completion, tools, thinking | 131072 | `think` takes `"low"|"medium"|"high"` |
| `nomic-embed-text:latest` | embedding | 2048 | Not a chat model — hide from the picker |

Model names change (v1 used `gemma4:12b`; it was later replaced by `gemma4:12b-it-q8_0`). **Never
hard-code a model name** except as a *preference* when choosing a default.

## 2. `POST /api/chat` — request

```json
{
  "model": "gemma4:e4b-it-q8_0",
  "messages": [
    { "role": "system", "content": "<system prompt>" },
    { "role": "user", "content": "Weather in Boston?" }
  ],
  "stream": true,
  "think": false,
  "options": { "num_ctx": 16384 },
  "tools": [ { "type": "function", "function": { "name": "...", "description": "...", "parameters": { "type": "object", "properties": { }, "required": [ ] } } } ]
}
```

Field rules (each learned the hard way):

| Field | Rule | Why |
|---|---|---|
| `think` | Always send `false` for chat (for `gpt-oss`, send `"low"` — it doesn't accept `false` well; verify) | gemma4 "thinks" by default: 1–2.5k hidden chars, **4–6.5 s → 0.5–0.9 s** per reply when off. Safe on non-thinking models (tested) |
| `options.num_ctx` | Always `16384`, identical on **every** request to a model (chat, transcription, helpers) | A different `num_ctx` reloads the model (3–4 s). The model's default context (up to 262144) made Ollama evict other models from VRAM |
| `tools` | Only when the model has the `tools` capability | Ollama rejects tools for models without it |
| `images` (per message) | base64 strings, no `data:` prefix. **Also carries audio** (WAV or MP3 only) | See §5 |
| `keep_alive` | Leave unset unless the owner decides otherwise (`06-decisions.md` D8) | Default 5 min idle unload; keeping models loaded holds ~14 GB VRAM |

## 3. Responses

### 3.1 Non-streaming (`"stream": false`) — a tool call (real)

```json
{"model":"gemma4:e4b-it-q8_0","created_at":"2026-10-29T18:30:33Z",
 "message":{"role":"assistant","content":"",
   "tool_calls":[{"id":"call_kifbfv45","function":{"index":0,"name":"get_weather","arguments":{"location":"Boston"}}}]},
 "done":true,"done_reason":"stop", "total_duration":..., "eval_count":...}
```

- Tool calls are **inside `message`**, never at the top level.
- `function` is an **object**; `arguments` is a **JSON object** whose values may be strings, numbers,
  booleans, arrays or objects. Model tool calls may also carry `id` and `function.index`.
- v1's DTO declared `function: String` → every real tool call threw
  `JsonSyntaxException: Expected a string but was BEGIN_OBJECT at path $.message.tool_calls[0].function`
  and the whole reply failed. Model this with `JsonObject` / `JsonElement` arguments.

### 3.2 Streaming (`"stream": true`) — NDJSON, one JSON object per line

Text reply (real, round 2 of the weather exchange; 29 lines total):
```
{"model":"gemma4:e4b-it-q8_0","created_at":"...","message":{"role":"assistant","content":"The"},"done":false}
{"model":"gemma4:e4b-it-q8_0","created_at":"...","message":{"role":"assistant","content":" weather"},"done":false}
{"model":"gemma4:e4b-it-q8_0","created_at":"...","message":{"role":"assistant","content":" in"},"done":false}
...
{"model":"gemma4:e4b-it-q8_0","created_at":"...","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","total_duration":257684900,"load_duration":2606000,"prompt_eval_count":118,"prompt_eval_cached_count":67,"prompt_eval_duration":30842000,"eval_count":...}
```

Tool call while streaming (real): the calls arrive **whole, in one line** with `done:false`, followed
by a final `done:true` line with empty content:
```
{"model":"gemma4:e4b-it-q8_0","created_at":"...","message":{"role":"assistant","content":"","tool_calls":[{"id":"call_rf7td9i2","function":{"index":0,"name":"get_weather","arguments":{"location":"Boston"}}}]},"done":false}
{"model":"gemma4:e4b-it-q8_0","created_at":"...","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop",...}
```

Parser rules: read line by line; concatenate `message.content` deltas; collect every `tool_calls`
array seen (there may be several lines); stop at `done:true`; tolerate unknown fields
(`ignoreUnknownKeys = true`); a line that isn't valid JSON is a `MalformedResponse` error.

### 3.3 Errors (real)

```
HTTP 404  {"error":"model 'no-such-model' not found"}
```
Errors are JSON `{"error": "..."}` with a non-200 status. Map 404 + "not found" → `ModelNotFound`;
connection refused / unknown host → `ServerUnreachable`; read timeout → `Timeout`.

## 4. Tool calling — the full protocol (verified two-round exchange)

```
→ request 1: messages [system, user "Weather in Boston?"], tools [get_weather]
← message: {role: assistant, content: "", tool_calls: [{id, function:{name:"get_weather", arguments:{location:"Boston"}}}]}

→ request 2: messages [system, user, <the assistant message above, unchanged, with tool_calls>,
                       {"role":"tool","tool_name":"get_weather","content":"Boston: 19°C, sunny, wind 19 km/h SW."}]
             tools [get_weather]
← streamed text: "The weather in Boston is currently 19°C and sunny, …"
```

Rules:
1. Append the assistant message **exactly as received** (including `tool_calls`) before the results.
2. One `{"role":"tool","tool_name":<name>,"content":<result text>}` per call, in call order.
3. Repeat while the reply contains tool calls; **cap at 5 rounds**, then make one final request
   **without** `tools` so the model answers with what it has.
4. Results are plain text, short (≤ ~2,000 chars), factual, and say clearly when something failed or
   is unavailable ("Couldn't get the weather for X: the weather service didn't respond.").
5. Never send `role: "system"` messages for tool results (v1 did; see lessons L3).
6. Tool exchanges are **not persisted** in chat history (01-architecture §5.2).

### 4.1 History poisoning (measured)

With v1's saved history (114 messages, 9 containing *text-written* tool calls like
`<tool_call>get_weather{location:<|"|>Boston<|"|>}</tool_call>`), the same model answered a weather
question with a **text** tool call **3/3 times**; without that history it produced a **real** tool call
**3/3 times** — even with the full persona prompt and 17 tools. System prompt length and tool count
were not the cause. Conclusions:
- Never persist tool exchanges or tool-ish text as conversation.
- `ContextBuilder` strips any `<tool_call>…</tool_call>` text and drops any non-`user`/`assistant` rows
  from history before sending (defensive, in case a model writes one anyway).
- Don't let a model's failed or apologetic tool replies accumulate: if a turn's tool calling failed,
  store the user-facing error, not a fabricated assistant apology.

### 4.2 Text-form tool calls (fallback)

gemma4's native call syntax can leak into `content` as `<tool_call>name{key:<|"|>value<|"|>}</tool_call>`
when Ollama's parser doesn't recognise it. Jarvis (the owner's desktop assistant, which tool-calls
successfully) also repairs calls written as `<tool_call>` tags, `[TOOL_CALLS]`, `<|python_tag|>`,
`<function=name>` or bare JSON. C08 includes an optional, tested fallback parser for these formats;
when it fires, log it (it indicates a prompt/history problem).

## 5. Images and audio input

- Per message: `"images": ["<base64>", ...]` (no `data:` prefix). Only for models with `vision`.
- **Audio uses the same `images` field** for models with `audio` (gemma4). Accepted formats: **WAV and
  MP3 only** — 3GP/AMR and M4A return `HTTP 400 "Failed to load image or audio file"`. Convert other
  formats to 16-bit PCM mono WAV on the phone.
- Verified: an 86-second WAV was fully transcribed in one request.
- Transcription prompt that works (gemma4):
  `Transcribe this audio verbatim. Reply with only the transcript, no commentary. If there is no
  intelligible speech, reply with exactly [NO_SPEECH] and nothing else.`
  Without the `[NO_SPEECH]` instruction, silence produced "I'm sorry, but I can't hear any speech in
  this audio" — which v1 then sent as the user's message. Treat a reply containing `[NO_SPEECH]` as empty.
- Send media only for recent messages (v1 used the last 10) and labels for older ones.

## 6. Other endpoints

- `GET /api/tags` → `{"models":[{"name":"gemma4:e4b-it-q8_0", ...}]}` — installed models.
- `POST /api/show {"model": "..."}` → `{"capabilities":["completion","vision","audio","tools","thinking"], "model_info": {"<arch>.context_length": 131072, ...}, ...}`.
  Cache per model name for the session; refresh when the picker opens.
- `GET /api/version` → `{"version":"0.40.13"}` — cheap reachability check.
- `GET /api/ps` → loaded models (useful for diagnostics screens).

## 7. Performance facts (owner's PC)

| Situation | Time |
|---|---|
| Chat reply, thinking on (default) | 4.0–6.5 s |
| Chat reply, `think:false`, fresh history | 0.5–0.9 s |
| Chat reply, `think:false`, long history (100+ msgs) | 1.8–4.3 s |
| Model reload after `num_ctx` change or idle unload | 3–4.5 s |
| Two-round tool exchange, warm (`e4b`) | ~5 s total (4.3 s + 0.7 s) |
| Transcription of a 10 s memo | 1–3 s |

## 8. Probing Ollama directly (for evidence, not CI)

Small Node scripts (`node probe.mjs`) with `fetch("http://localhost:11434/api/chat", …)` were the
fastest way to test prompts, tools and audio in v1 without rebuilding the app. Run on the PC.
`%LOCALAPPDATA%\Ollama\server.log` lists every request (`| 200 | 4.28s | 192.168.4.82 | POST "/api/chat"`)
— diff it before/after an app test to prove what the app sent.

## 9. Things Ollama can't do on the owner's Windows PC (as of 0.40.x)

- **Text-to-speech** (`phonon` via `/v1/audio/speech`): needs Apple MLX → Apple Silicon Macs only.
- **Image generation** (`x/z-image-turbo`, `flux2-klein`): macOS only at launch, Windows "planned".
Both may change; check before assuming either way.

# C41 — Tools on iOS: the tool loop, time, weather and the phone's location

| | |
|---|---|
| Phase | 9 — iOS (Expo) |
| Status | **Built** in Expo Go; see "As built" at the end. The owner's iPhone check is open |
| Depends on | C40 (Stage 1: chat in `ios-app/`) |
| Ports | C08 (tool framework), C09 (weather), C11 (device location), the `get_current_time` tool of C08 §7a |
| Not here | Web search and page reading (C10), confirmations, accounts, calendar, mail, anything that changes data |

## Why
The C40 roadmap puts tools first: "What's the weather in Boston?" and "What time is it in Tokyo?" are the questions the
owner asks most, they need no sign-in and no paid Apple account, and they exercise the tool loop every later feature
(calendar, mail, to-dos, Jarvis) will reuse. Android's behaviour is the specification (C08, C09, C11 and their
"As built" sections); the Kotlin code is not copied.

## Specification (differences from Android only)
- **Framework** (`ios-app/src/tools/framework/`): `Tool`, `ToolSpec`, `toOllama`, `ToolArgs` (tolerant arguments: strings to
  numbers and booleans, a declared alias or a case/underscore-insensitive key, missing required → error text for the
  model), `ToolRegistry` (only a model with the `tools` capability is offered tools; name repair: exact, `functions.`
  prefix, `-`/`.`/case, one typo for names of 8+ characters), `toolGuidanceSection` (the system-prompt paragraph, or
  "You can't look things up right now…" for a model without tools, or nothing), and `runToolTurn` (the loop).
- **The loop** is C08 §5: up to 5 rounds, then one more request **without** `tools`; every tool runs with a 20 s timeout;
  a failure, a timeout or an unknown tool becomes text for the model; the assistant message with `tool_calls` goes back
  exactly as received and results are `role:"tool"` with `tool_name`; the stored reply is the final round's text, plus
  the tool names for the "Used: …" line (`messages.tools_used`, already in schema v2). The exchange is never stored (L3).
  Text the model wrote before a tool call is cleared (`onTextReset`), and speech stops speaking it.
- **Tools:** `get_current_time(timezone?)`, `get_weather(location, days)` and, inside weather, "here".
- **Weather** is Open-Meteo through `httpClients`-equivalent `httpFetch` (HTTPS, no key), parsed only from responses
  captured live (fixtures in `src/tools/weather/fixtures/`, including the no-results response that has no `results` key).
  Place hints, the most-populous rule with an ambiguity note, WMO words, 400-character result: as C09.
  Units follow the phone's region (D7: US, Liberia and Myanmar get °F and mph, everywhere else °C and km/h); there is no
  override screen yet.
- **Location** (`expo-location`): "while using" permission only, balanced accuracy, 10 s timeout, never stored. iOS shows its
  own permission dialog the first time a tool needs "here" (D14: no Edith card first). A denial gives the model "The user
  hasn't shared their location — ask for a city." and no crash.
- **Not in this chunk:** confirmations (no tool here changes anything, so D11 doesn't apply yet), the text-written
  tool-call fallback parser (C08 §7), and reverse geocoding of the position.

## Tests
Jest, with a scripted Ollama and the live-captured fixtures: argument coercion and aliases, registry resolution and
guidance, the loop (one round, two tools, several rounds, the cap, unknown tool, missing argument, a throwing tool, a
timeout, the result limit, a model without tools, cancel), the engine storing only the answer and `tools_used`, the
time tool and zone offsets, weather (fixtures, URL encoding, hints, ambiguity, no place, HTTP 503, "here", denial) and
the "Used:" line.

## Acceptance criteria
- [x] `scripts/ios-check.sh` is green.
- [ ] **The owner, on the iPhone in Expo Go**, with the real server: "What time is it?" shows "Used: time"; "What's the
      weather in Fowlerville?" and "Will it rain in Chicago tomorrow?" give real answers that match open-meteo.com;
      "What's the weather here?" shows iOS's location dialog once, then real weather; Don't Allow gives a plain ask for a
      city; a model without tools says it can't look things up; Stop during a tool turn works.

## As built (2026-11-20)
Built by an AI agent in `ios-app/src/tools/`; Android is untouched. Where the code differs from the specification above,
the code is right.
- Files: `tools/framework/` (`tool.ts`, `toolArgs.ts`, `toolRegistry.ts`, `toolRunner.ts`), `tools/context/currentTimeTool.ts`,
  `tools/weather/` (`weather.ts`, `openMeteo.ts`, `weatherTool.ts`, `fixtures/`), `tools/location/deviceLocation.ts`.
  `ChatEngine` takes `tools` and runs `runToolTurn` instead of a single stream; `core/services.tsx` registers the tools;
  `describeMoment` in `chat/promptBuilder.ts` is shared by the date line and the time tool; `app.json` has the
  `expo-location` plugin with the permission text (used by builds; Expo Go has its own).
- The `noToolsSection` of C40 is replaced by `toolGuidanceSection`.
- **Not verified:** everything on a real iPhone (this agent has no device), and whether `Intl` time zones other than the
  phone's work in Hermes on iOS (the tests ran on Node; the time tool reports an unknown zone as an error if `Intl`
  throws, so it can't crash).

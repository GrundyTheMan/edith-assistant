# C09 — Weather tool (Open-Meteo)

| | |
|---|---|
| Phase | 2 |
| Status | **Done** — see "As built" at the end |
| Depends on | C08 (C11 adds "here") |
| Size | Small–medium |
| Read first | C08 spec; 04-lessons L5; 06-decisions D7 |

## Goal

"What's the weather in Fowlerville?" → real current conditions (and a short forecast when asked), from
Open-Meteo: free, no API key, structured JSON. v1's wttr.in integration failed on a JSON array/object
mix-up (L5); parse only from captured real responses.

## Specification

### 1. Endpoints (verified live 2026-10-29)
Geocoding:
```
GET https://geocoding-api.open-meteo.com/v1/search?name=Fowlerville&count=5&language=en&format=json
→ {"results":[{"id":4993272,"name":"Fowlerville","latitude":42.66059,"longitude":-84.07301,"country_code":"US",
     "timezone":"America/Detroit","population":2895,"country":"United States","admin1":"Michigan","admin2":"Livingston", …}], …}
```
(no `results` key at all when nothing matches.)

Forecast:
```
GET https://api.open-meteo.com/v1/forecast?latitude=42.66&longitude=-84.07
    &current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,precipitation
    &daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max
    &temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&forecast_days=3
→ {"timezone":"America/Detroit","current_units":{"temperature_2m":"°F", …},
   "current":{"time":"2026-10-29T14:30","temperature_2m":72.3,"apparent_temperature":65.7,"relative_humidity_2m":46,
              "weather_code":2,"wind_speed_10m":16.1,"precipitation":0.00}, "daily": { … arrays … }}
```
Capture both (and a "no results" geocoding response) as fixtures under `app/src/test/resources/weather/`.

### 2. `OpenMeteoClient` (`tools/weather/`)
`suspend fun geocode(name: String): Outcome<List<Place>>`, `suspend fun forecast(lat, lon, units): Outcome<Forecast>`.
kotlinx.serialization DTOs with `ignoreUnknownKeys`. URL-encode the place name. Timeouts 10 s.

### 3. Place resolution
- Input may be "Fowlerville", "Fowlerville, MI", "Paris, France", "Springfield". Split on comma: first part
  is the name; the rest is a region/country hint matched against `admin1`, `country`, `country_code`
  (US state abbreviations map to names: MI → Michigan, etc. — include a small table).
- Prefer the hint match; else the most populous result; if several plausible places tie (e.g. "Springfield"
  with no hint), use the most populous **and** say which one in the result ("Springfield, Illinois — say a
  state if you meant another").
- `here`/`my location`/empty → C11's device location (until C11: result "I need your location — ask the user for a city.").

### 4. Weather codes → words
Map WMO codes (0 clear, 1 mainly clear, 2 partly cloudy, 3 overcast, 45/48 fog, 51–57 drizzle, 61–67 rain,
71–77 snow, 80–82 showers, 85–86 snow showers, 95–99 thunderstorm) to short phrases.

### 5. Tool
`get_weather(location: String, days: Integer = 0)` — `days` 0 = current only, 1–3 adds the daily forecast.
Units from settings (`auto` → `Locale` country US/LR/MM → imperial, else metric).
Result text (≤ 400 chars), e.g.
`Fowlerville, Michigan (US) — now 72°F (feels like 66°F), partly cloudy, wind 16 mph, humidity 46%. Tomorrow: high 70°F, low 52°F, 20% chance of rain.`
Errors: "Couldn't find a place called X." / "The weather service didn't respond — try again shortly."
Status label: "Checking the weather…".

## Tests
- Parse the real fixtures (current + daily); unit conversion parameters chosen from settings.
- Place resolution: "Fowlerville, MI" picks Michigan; "Springfield" picks the most populous and notes ambiguity; no results → error text.
- Weather code mapping covers all listed codes.
- Tool end-to-end with MockWebServer for both endpoints (base URLs injectable).

## Acceptance criteria
- [ ] Tests pass.
- [ ] Live: "What's the weather in Fowlerville?", "…in Boston?", "Will it rain in Chicago tomorrow?" each produce
      a real answer that matches open-meteo.com at the time (paste the app log + reply in the PR).
- [ ] Network off → a clear message from Edith, no crash.

## As built (2026-11-01)

Where the code differs from the specification above, the code is right.

**Files**
- `tools/weather/OpenMeteoClient.kt`: the client, plus DTOs taken from responses captured live on 2026-11-01.
- `tools/weather/Weather.kt`: `Place`, `Forecast`, `WeatherUnits` (D7), `describeWeatherCode` and `PlaceResolver`.
- `tools/weather/WeatherTool.kt`: `get_weather`.
- `AppContainer` adds `openMeteo` and registers `WeatherTool`.
- Test fixtures in `src/test/resources/weather/`: Fowlerville, Springfield (5 results), no results (just
  `{"generationtime_ms":…}` with no `results` key), an imperial forecast and a metric one.

**Differences from the spec**
- **Units (D7, owner 2026-11-01):** automatic from the phone's country. US, Liberia and Myanmar get °F and mph;
  everywhere else gets °C and km/h. The Settings value `units` (Auto / Metric / Imperial, C04) can force either;
  there's no screen for it yet.
- **Geocoding asks for 10 results**, not 5, so a state hint has more candidates. Only the name before the first
  comma is sent; the rest is the hint.
- **Ambiguous names:** the result says "(other places share this name — say a state or country if you meant
  another)", and the model passes that on.
- **The forecast `days` value** is clamped to 0–3 and the reply cut to 400 characters. Days after tomorrow are named
  by weekday.
- **"here" / "my location" / "near me"** returns "I need your location — ask the user for a city." until C11.
- **The HTTP client** shares the app's OkHttp client, with its own 10 s timeouts. A non-200 answer, a network error
  or a garbled reply all become "The weather service didn't respond — try again shortly."
- **No regexes**, so nothing depends on Android's regex engine (L25). The tests are JVM tests over the real fixtures
  and MockWebServer, and the live check covered the device.

**Verified**
- `scripts/check.sh --device`: OK (71 device tests), 91 unit tests, lint clean
  (`build-logs/2026-11-01_00-34-14_check.log`).
- `WeatherToolTest` (10, JVM) covers:
  - parsing all the fixtures
  - URL-encoding ("São Paulo & Co")
  - the units sent and the unit choice by country
  - hint matching ("IL", "massachusetts")
  - the most-populous rule, and the ambiguity note
  - every listed WMO code
  - the tool end to end: Fowlerville, a 2-day forecast, Paris in metric, Springfield, no match, "here", HTTP 503,
    a garbled reply, no server, and a missing location
- **Live on the emulator** (`gemma4:12b`, 2026-11-01 00:14) against Open-Meteo queried directly at the same moment.
  Edith added "MI", "MA" and "IL" herself:

  | Question | Tool call | Tool result | Open-Meteo directly |
  |---|---|---|---|
  | What is the weather in Fowlerville? | `{"location":"Fowlerville, MI"}` | 50°F (feels 45°F), overcast, wind 6 mph, humidity 71% | 50.0°F, feels 45.2°F, code 3, 6.2 mph, 71% |
  | What is the weather in Boston? | `{"location":"Boston, MA"}` | 36°F (feels 29°F), clear, wind 4 mph, humidity 65% | 36.0°F, feels 29.4°F, code 0, 4.4 mph, 65% |
  | Will it rain in Chicago tomorrow? | `{"days":1,"location":"Chicago, IL"}` | Tomorrow: high 61°F, low 51°F, 24% chance of rain | max 60.8°F, min 51.4°F, 24% |

  Edith's replies: "Fowlerville is currently looking quite moody and overcast, sitting at a brisk 50°F…"; Chicago:
  "…There's a 24% chance of drizzle tomorrow in Chicago, with a high of 61°F. It's not exactly a washout…".
- **Network off** (airplane mode): the turn fails clearly, with "Can't reach your Ollama server at
  http://10.0.2.2:11434…" and Retry, and nothing crashes. With all networking off Ollama is unreachable too, so the
  weather service failing alone is covered by the tests (closed server, 503, garbled reply).
- **Test reliability:** `ChatScreenTest` failed twice in full runs right after the live session: "Stop reply" and
  the other-chat hint weren't found. It then passed alone twice and in three full runs. Those two checks now wait up
  to 10 s, and their timeout message dumps the screen and engine state, in case it recurs.

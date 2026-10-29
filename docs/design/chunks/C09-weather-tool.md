# C09 — Weather tool (Open-Meteo)

| | |
|---|---|
| Phase | 2 |
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

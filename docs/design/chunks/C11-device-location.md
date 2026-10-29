# C11 — Device location ("here")

| | |
|---|---|
| Phase | 2 |
| Depends on | C08, C09 |
| Size | Small |
| Read first | 01-architecture §9 (permissions) |

## Goal

"What's the weather here?" / "near me" works using the phone's location — asked for only when needed,
coarse only, never stored.

## Specification
- Permission: `ACCESS_COARSE_LOCATION` only (no fine, no background). Requested the first time a tool needs
  "here", via an Activity Result launcher triggered from the confirmation-style card in the chat: Edith's
  in-flight bubble shows "Edith needs your approximate location to answer that." [Allow] [Not now].
  Implement through C08's confirmation mechanism (`ConfirmationRequest` with `toolName = "location"`), then the
  system permission dialog.
- `LocationProvider.current(): Outcome<Coordinates>` using `LocationManager` (`getCurrentLocation` on API 30+,
  `requestSingleUpdate`/last known on older) with `PRIORITY_BALANCED`/network provider; timeout 10 s;
  no Google Play services dependency.
- Reverse geocode for display with `android.location.Geocoder` when available (on-device), else Open-Meteo
  nearest place isn't available → say "your current location".
- `get_weather` accepts `location` = "here" / "current location" / empty → uses the provider. Settings
  `use_device_location` toggles whether Edith may ask (default off → she asks for a city instead).
- Never store coordinates; never send them to Ollama except as part of a tool result's place name.
- Optional prompt line when enabled: "If the user says 'here', use their current location via tools."

## Tests
- Fake `LocationProvider`: "here" resolves to coordinates → forecast called with them.
- Permission denied → tool result "The user hasn't shared their location — ask for a city." and no crash.
- Setting off → tool asks for a city without requesting permission.

## Acceptance criteria
- [ ] On the emulator (set a location in Extended controls → Location): "What's the weather here?" → permission
      card → system dialog → real weather for that location.
- [ ] Denying works gracefully; the app never requests fine or background location.

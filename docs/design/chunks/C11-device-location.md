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
- [x] On the emulator (set a location in Extended controls → Location): "What's the weather here?" → ~~permission
      card →~~ system dialog → real weather for that location. (No card: D14.)
- [x] Denying works gracefully; the app never requests fine or background location.

## As built (2026-11-01)

Where the code differs from the specification above, the code is right.

**Files**
- `core/permissions/ToolPermissions.kt`: carries a tool's permission request to the chat screen and the answer back.
- `tools/location/DeviceLocation.kt`: `Coordinates`, `LocationProvider`, `AndroidLocationProvider`.
- `tools/framework`:
  - `Tool.permission(args)` and `PermissionNeed`.
  - `ToolTurnRunner` asks for the permission before the confirmation step and before `execute`.
- `tools/weather/WeatherTool.kt`: "here", near me, or no place uses the device.
- `chat/ui/ChatScreen.kt` / `ChatViewModel.kt`: show Android's dialog for a waiting request.
- `AppContainer`: `toolPermissions` and `deviceLocation`. The manifest adds `ACCESS_COARSE_LOCATION`, the only location
  permission in the merged manifest (checked).

**Differences from the spec**
- **No Edith card and no setting (D14, owner 2026-11-01).**
  - The first "here" goes straight to Android's dialog, which offers approximate location only (While using the
    app / Only this time / Don't allow).
  - `Settings.useDeviceLocation` (C04) was removed. It had no screen and defaulted off, so "here" could never have
    worked. A value saved under its old key is simply ignored.
- **How the permission is asked:** not through `Confirmations`, but through a separate step built the same way.
  - A tool declares `permission(args)`. If the permission isn't granted, the runner shows "Waiting for your
    permission…" and publishes a request; the chat screen launches Android's dialog and resolves it.
  - The wait sits outside the tool's 20 s timeout, so a slow tap doesn't fail the call. Stop cancels it.
  - The dialog is launched once per request: a rotation doesn't show it twice.
  - If the Chat tab isn't showing, the request waits until it is.
  - A denial gives the model "The user hasn't shared their location — ask for a city." Once Android stops showing
    the dialog (after two denials), the answer is an immediate denial, with the same message.
- **Getting the location:** `LocationManager` through `LocationManagerCompat.getCurrentLocation` (no Play services).
  - Providers: fused (Android 12+), network, GPS, sharing one 10 s budget. Then a last-known fix under 10 minutes old.
  - **Fix, found live:** the fused provider first had the whole 10 s. With coarse permission it uses network
    location only and never switches GPS on, so on the emulator (no network location; its test location comes
    through GPS) it timed out and GPS was never asked. A phone usually gets network location from Wi-Fi or cell,
    but GPS is needed as a fallback anyway.
  - Location off → "Location is turned off on the phone…". No fix → "Couldn't find the phone's location just now…".
- **Privacy:**
  - Coordinates are rounded to 2 decimals (about 1 km) before use; Android already blurs coarse location to
    about 2 km.
  - They go only to Open-Meteo's forecast call (HTTPS). They're never stored or logged (checked: the app's logcat
    had no coordinates after a live run), and never sent to Ollama.
  - The model sees the place name from Android's `Geocoder` ("Fowlerville, Michigan (US)"), or "Your current
    location" when the phone can't name it.
- **The prompt line** is `WeatherTool.guidance`: "If the user says "here" or "near me", or doesn't name a place, call
  get_weather with location "here"." `location` is no longer required.

**Verified**
- `scripts/check.sh --device`: OK, 109 unit tests, lint clean, 72 device tests
  (`build-logs/2026-11-01_09-25-22_check.log`).
- `WeatherToolTest`: 3 new tests. With a fake location, "here" calls the forecast with the coordinates and no
  geocoding. The permission is needed for here / near me / current location / empty, and not for a named place.
  There are also tests for the unnamed place, location turned off, and coordinate rounding.
- `ToolTurnRunnerTest.t13` (device):
  - already granted → runs, nothing asked
  - asked, then allowed after longer than the tool timeout → runs
  - denied → not run, and the model gets the denial
  - Stop while waiting → nothing runs, and the request is cleared
- **Live on the emulator** (`gemma4:12b`, test location Fowlerville, MI):
  - **Don't allow:** `get_weather({"location":"here"}) -> permission denied`. Edith: "…Could you tell me which city
    you're in?" No crash.
  - **Only this time:** "It's a bit of a grey morning in Fowlerville, with overcast skies and a crisp 55°F. It feels
    slightly cooler—about 51°F…". Open-Meteo directly: 54.8°F, feels 50.6°F, overcast, 6.5 mph, 68%.
- **Not verified:**
  - **A real phone**, and its network location.
  - **Android 8–11** (minSdk 26): the code avoids GPS there if it's refused, but only API 37 was run.
  - **"While using the app"**: only "Only this time" and "Don't allow" were tapped.

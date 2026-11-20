# C46 — Smart home core on iOS

| | |
|---|---|
| Phase | 9 — iOS (Expo) |
| Status | **Built** in Expo Go, with the Demo home only; see "As built" at the end. The owner's iPhone check is open |
| Depends on | C41, C45 (confirmations) |
| Ports | C25 (the core), D19 |
| Not here | Any real service (Meross, Ring, Alexa, ... each is its own chunk), cameras and `camera_snapshot` (tool results can't carry pictures yet), the "screen-only" rule for hands-free (no hands-free on iOS), the command log in SQLite (it is in the key-value store) |

## Why
Everything the shared home code needs, built once against a fake service, so each real service later adds only a
backend (C25's idea). This also gives the owner the Home tab and `home_devices` / `home_control` to try.

## Specification (differences from Android only)
- **Model** (`home/model.ts`): `DeviceType`, `Capability`, `DeviceState` (every field optional), `HomeDevice`,
  `DeviceCommand`, and `HomeBackend` (`devices()`, `state()`, `command()`, `isHub`). No cameras yet.
- **Registry** (`home/home.ts`, `Home`): reads every service in parallel with a 10 s limit each; a service that fails
  or is slow is a problem line and keeps its last-known devices; a hub's copy of a device a direct service reports is
  dropped (and comes back when the direct one fails). Favourites, hidden devices, the selected chip and the Demo switch
  are remembered (key-value store). Hidden devices are left out of the tools and the tab.
- **Names** (`home/names.ts`): exact, "room name" / "name in room", the only name containing the words, a copied
  "(Light)" ignored; ambiguous → the tool lists up to 10 and Edith asks.
- **Tools** (`home/homeTools.ts`): `home_devices(filter?)` (by room, at most 80, a service that didn't answer is named)
  and `home_control(device, action, value?)` (the 12 actions; what a device can't do is refused with what it can;
  setpoints 50–90 °F / 10–32 °C; fan words low 33, medium 66, high 100). Both are offered only while a service is
  connected. After a command the result says what the device now reports.
- **Cards** (C45): unlock, open, close and any thermostat mode change always ask; a setpoint asks when it moves more
  than 5 °F / 3 °C or the current one is unknown; lock, lights, plugs and fans never ask.
- **Re-check**: the device is asked again 3 s later; locks, doors and thermostats also at 10 s and 30 s.
- **Home tab**: chips (Favorites, Lights, Plugs and switches, Fans, Climate, Locks and doors, All; the last one is
  remembered), tiles by room with the state and its age ("On, 60% · 3 min ago", "Not responding"), one tap on the power
  button toggles, a tap on the name opens controls (brightness presets 10–100 %, white tones, fan Low/Medium/High,
  thermostat − / + sent 1.2 s after the last tap, mode chips, star, Hide). Locks and doors are in their own section with
  Lock/Unlock and Open/Close buttons; Unlock, Open and Close ask on a dialog first. A service that can't be reached shows a
  banner. Pull to refresh. (Brightness uses presets rather than a slider.)
- **Settings → Home**: the **Demo home** switch (development builds, which Expo Go is), hidden devices with Show again,
  and the command log (newest 500 kept; the newest 30 shown). Commands from chat and from the Home tab are both logged.
- **Demo home** (`home/demoBackend.ts`): a porch light (dimmable), a living-room lamp (brightness, colour, white), a
  kitchen plug, a bedroom fan, a hallway thermostat (°F; heat, cool, auto, off), front and back door locks, and the
  garage door. Locks, the door and the thermostat report a change 4 s late.

## Tests
Name resolution; the actions (refusals, values, limits) and the card rules; the registry (one service failing keeps
its devices, a slow one, hub merging both ways, preferences kept across a reload); the Demo home (a light, a lagging
lock and its re-checks, an error, the 500-entry cap); both tools (listing, filtering, hidden devices, doing things,
ambiguity doing nothing, approval for unlock/open only, not offered with no service); the chips, the screen's
ask-first rule, and the Home tab.

## Acceptance criteria
- [x] `scripts/ios-check.sh` is green.
- [ ] **The owner, on the iPhone**: Settings → Home → Demo home on; the Home tab shows rooms and the locks section;
      toggle the porch light; open the thermostat controls and step it; Unlock the front door → dialog → it shows Locked
      then Unlocked a few seconds later; in chat "turn on the porch light", "set the bedroom fan to low", "unlock the
      front door" (a card in Edith's bubble; Cancel → nothing happens); star and Hide work; the command log lists them.

## As built (2026-11-20)
Built by an AI agent; Android is untouched. Where the code differs from the specification above, the code is right.
- Files: `home/` (`model.ts`, `names.ts`, `actions.ts`, `home.ts`, `demoBackend.ts`, `homeTools.ts`, `home.test.tsx`),
  `ui/HomeScreen.tsx`, `ui/HomeSettings.tsx`, `app/(tabs)/home.tsx`; changes in `core/services.tsx` (`home`),
  `app/(tabs)/_layout.tsx` (the tab), `ui/SettingsScreen.tsx`, and the tool framework (`Tool.enabled`, and
  `Tool.confirmation` may be async).
- **Not verified:** everything on a real iPhone (layout, the dialog, the tab bar with four tabs, how the models use
  the tools).

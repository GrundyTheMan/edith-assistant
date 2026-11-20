# C48 — Home services framework on iOS

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave A |
| Status | **Design** |
| Depends on | C46 (the home core), C45 (confirmations) |
| Unblocks | C50 Meross, C51 Ring, C52 Blink, C54 Alexa, C55 Printer, C70, C73, C74 (every real home service) |
| Runs in | Expo Go |
| Size | Medium |
| Ports | C25 §7 (Settings → Home), C26's generic parts (`HomeService`, the service row, the shared sign-in dialog, `changedAt`), C37 (HTTPS-only), C02's `SecretStore` |
| Read first | `ios-roadmap.md` §2–3; C25 and C26 (spec **and** As built); C37 (the whole chunk: it explains why `secureFetch` exists); 04-lessons L6, L27, L36 |

## Why
C46 built the home core against a Demo home. Every real service (Meross, Ring, Blink, Alexa, the printer, later Resideo and
Philips) needs the same four things: a **secure place for its token**, a **common shape** ("a service that can sign in,
sign in again, disconnect, and turns into a `HomeBackend`"), a **Settings → Home row and sign-in form** the owner uses, and a
**network wrapper that cannot leak a credential over plain HTTP or to another host**. Android got these piecemeal across C26
and C37 and fixed two bugs afterwards. Build them once, first, so the six service chunks can run in parallel and only add
their protocol.

This chunk also adds, **up front**, every optional `HomeBackend` method the later chunks need (snapshots, events, clips, live
view) so the parallel chunks do not edit the same interface at the same time.

## Specification

### 1. `SecretStore` (`core/secrets.ts`)
```ts
export interface SecretStore {
  get(key: string): Promise<string | null>;
  set(key: string, value: string): Promise<void>;
  delete(key: string): Promise<void>;
}
export const keychainSecrets: SecretStore   // expo-secure-store
export function memorySecrets(initial?: Record<string,string>): SecretStore   // tests
```
- `keychainSecrets` uses `expo-secure-store` (`getItemAsync`, `setItemAsync`, `deleteItemAsync`) with
  `keychainAccessible: WHEN_UNLOCKED_THIS_DEVICE_ONLY` (not backed up, not moved to another phone). Install with
  `npx expo install expo-secure-store`; it is in Expo Go. Keys may contain only letters, digits, `.`, `-`, `_`: use
  `home.meross`, `home.ring`, `account.<id>.refresh_token` (dots, not slashes).
- A value is a string; services store **JSON** (`JSON.stringify({token, key, userId, …})`).
- Values larger than ~2 KB are not allowed by iOS Keychain in SecureStore: if a service needs more, store it in two keys. (None of the planned services does.)
- Wire it in `createServices` as `secrets` and add `secrets: SecretStore` to `Services`.
- A secret is never logged, never put in an `Error` message, never in `AppError`, never in a thrown object that reaches the UI.

### 2. `secureFetch` (`core/network.ts`)
Wrap the existing `httpFetch` (an `HttpFetch`: see `ollama/client.ts`) in `secureFetch(base: HttpFetch, opts?)`:
- **Refuses any URL that is not `https:`** (returns a rejected promise with an `AppError('http_blocked', …)` that names the host, never the URL's query or credentials).
- **Redirects:** `expo/fetch` follows redirects by default. Where it exposes `redirect: 'manual'`, use it, follow at most 3
  redirects yourself, and stop (reject) when a redirect leaves `https` or changes to a host that is not allowed by `opts.allowHosts` (a list of host suffixes, e.g. `['meross.com']`). If `redirect: 'manual'` is not supported
  in this SDK, **verify the final `response.url`** after the request and, if it left the allowed hosts, discard the body and fail (the request was already sent; document this limit in "As built", as the web-page reader's C42 does).
- Adds `User-Agent` only if the caller passes one (some services need a specific one).
- `httpClients`-like export: `export const secureHttp = secureFetch(httpFetch)`; services receive an `HttpFetch`, so tests pass `fakeFetch(...).fetch` directly.
- **Exceptions:** `plainHttp` (the existing `httpFetch`) stays for Ollama, the 3D printer, Meross LAN, and the web-page reader. A chunk that needs it says why in its PR (AGENTS.md rule).

### 3. `HomeService` (`home/service.ts`)
```ts
export type ServiceState =
  | { kind: 'signed_out' }
  | { kind: 'connecting' }
  | { kind: 'connected'; detail?: string }          // e.g. "3 devices", the account name
  | { kind: 'needs_sign_in'; reason: string }       // "Sign in again": a token was refused
  | { kind: 'error'; message: string };             // reachable problems in words

export interface SignInField { key: string; label: string; secret?: boolean; optional?: boolean; keyboard?: 'default'|'email'|'number'|'url'; hint?: string }
export type SignInForm = { title: string; intro?: string; fields: SignInField[]; submitLabel?: string }
export type SignInResult =
  | { kind: 'ok' }
  | { kind: 'needs_code'; form: SignInForm }          // a second step (2FA): Edith shows this form next
  | { kind: 'failed'; message: string };

export interface HomeService {
  id: string;                       // 'meross', 'ring', 'blink', 'alexa', 'printer', 'resideo', 'philips'
  name: string;                     // "Meross"
  state: Store<ServiceState>;
  signInForm(): SignInForm;
  /** Page-style sign-in (Alexa): when present the Settings row opens the page flow instead of the form. */
  pageSignIn?: PageSignIn;
  signIn(values: Record<string,string>): Promise<SignInResult>;   // values are the form's answers; never logged
  disconnect(): Promise<void>;                                     // returns the token to the service when it asks for that, then deletes secrets
  /** The backend that `Home` reads while the service is connected. */
  backend(): HomeBackend;
  /** Called once at startup: load secrets, set `state`. Must not throw. */
  restore(): Promise<void>;
}
export interface PageSignIn { startUrl(): string; /* where the page flow ends: */ isFinish(url: string): { code?: string } | null; complete(code: string): Promise<SignInResult> }
```
- `Home.register(service)` / `Home.services()` (a `Store<HomeService[]>`): `Home.setBackends` is now computed: `[demo if on] + [service.backend() for each connected service]`. Keep `setBackends` for tests. When a service's `state` changes, `Home` re-computes its backends and bumps its version.
- **Never retry in a loop.** A refused token → `needs_sign_in` once, not repeated calls (C26, C27, C29 traps). An unreachable server is `error`, not `needs_sign_in`.
- **Restore at startup** in `createServices` (`await Promise.all(services.map(s => s.restore()))`, each guarded so one failing service does not stop the app).

### 4. Extra optional `HomeBackend` methods (`home/model.ts`) — added now, implemented later
All optional; a backend that lacks one simply doesn't offer the feature.
```ts
timeoutMs?: number;                               // per-backend read limit; default 10 s (READ_TIMEOUT_MS)
snapshot?(deviceId: string, signal?: AbortSignal): Promise<{ jpegBase64: string; takenAt: number }>;   // C49, C51, C52
snapshotTimeoutMs?: number;                       // Ring 35 s
events?(deviceId: string | null, sinceMs: number): Promise<DeviceEvent[]>;                           // C51, C52
hasClips?: boolean;  clips?(sinceMs: number, untilMs: number): Promise<DeviceClip[]>;  clipUrl?(clipId: string): Promise<string>;   // C53
hasLive?: boolean;   startLive?(deviceId: string): Promise<LiveFeed>;                                // C71
```
Types: `DeviceEvent { id, deviceId, deviceName, kind: 'ring'|'motion'|'person'|'live'|'other', at: number }`,
`DeviceClip { id, deviceId, deviceName, backend, kind, at, durationSec }`, `LiveFeed` (declared as `unknown` placeholder here; C71 defines it).
Add `DeviceState.changedAt?: number` ("moved 17 h ago"), and the matching words in `stateWords` / the tile ("moved 17 h ago").
`Capability` gains `'snapshot' | 'events' | 'live' | 'print_control'`; `DeviceType` gains `'camera' | 'doorbell' | 'printer' | 'sensor' | 'speaker'`.
`HomeScreen` and `homeTools` already ignore types they don't know; add camera/doorbell/printer to the chip filters later in their own chunks.

### 5. Settings → Home (`ui/HomeSettings.tsx`)
- A **Services** list above the Demo switch: one row per registered service: name, a state line ("Connected · 3 devices",
  "Not connected", "Sign in again: <reason>", the error), and the right button: **Sign in** (opens the form), **Sign in again**
  (same form, title says "again"), **Disconnect** (asks "Disconnect <name>? Edith forgets the sign-in." then `disconnect()`).
- **The shared sign-in form** (`ui/ServiceSignIn.tsx`): a modal with the service's `SignInForm`; text fields (`secureTextEntry` for
  `secret`), a Submit button, a spinner while `signIn` runs, the error text from a `failed` result, and the second step when
  the result is `needs_code` (replace the fields by the code form, keep the first-step values in memory only). Autofill
  hints: `textContentType` `username`/`password`/`oneTimeCode` so iOS can offer the keychain. The values live in component
  state only and are cleared when the modal closes; **never** written to settings, logs or analytics.
- A **page sign-in** variant (`ui/PageSignInModal.tsx`, used by Alexa in C54): a `react-native-webview` showing `startUrl()`, watching `onNavigationStateChange` for `isFinish(url)`; "Use my browser" is not needed on iOS. Install
  `react-native-webview` with `npx expo install` (it is in Expo Go).
- Problem banners: `Home.problems` already shows per-backend failures; a service in `needs_sign_in` also shows a banner "Sign in to <name> again" with a button that opens its form.
- The Demo home switch stays (development builds only).

### 6. Command log source
`LogEntry.source` gains `'hands_free'`/`'voice'` later; no change now. The service id is added to each entry (`service`), shown in Settings → Home's log line.

### 7. Files
New: `core/secrets.ts`, `home/service.ts`, `ui/ServiceSignIn.tsx`, `ui/PageSignInModal.tsx`, tests `core/secrets.test.ts`,
`home/service.test.tsx`, `core/network.test.ts`. Changed: `core/network.ts`, `core/services.tsx`, `home/home.ts`, `home/model.ts`,
`home/actions.ts` (`stateWords` for `changedAt`), `ui/HomeSettings.tsx`, `ui/HomeScreen.tsx` (age words), `package.json` (+`expo-secure-store`, `react-native-webview`), `app.json` (plugins if the packages need them).

## Tests
- `secrets`: memory fake round trip; the Keychain wrapper is tested by injecting a fake `SecureStore` module (mock `expo-secure-store`).
- `secureFetch`: refuses `http://` (never calls the base fetch), allows `https://`, refuses a redirect to another host or to `http`, follows an allowed redirect, never puts a URL's credentials in the error.
- `HomeService` with a fake service: restore sets state; signing in updates state and `Home.backends`; a `needs_code` result shows the second form; `failed` shows the message and keeps the form; disconnect calls the service and clears state; a refused token turns into `needs_sign_in` once (a second call does not call the backend again).
- Settings → Home render test: rows, buttons by state, the form's secret field, the disconnect confirmation.
- `changedAt` words: "moved 17 h ago".

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green; `npx expo-doctor` still passes.
- [ ] **Owner, on the iPhone:** Settings → Home lists "No services yet" (until a service chunk lands) and the Demo switch works as before; the Demo home still behaves.
- [ ] A test service registered in a unit test appears with its row and form; no secret reaches a log (grep `log.` in the diff for the form values).

## Risks and unverified assumptions
- `expo-secure-store` size limit and Keychain behaviour under Expo Go (the Keychain is Expo Go's, so items **disappear when Expo Go is deleted**; in a development build they belong to Edith). Say this in the PR.
- Whether `expo/fetch` exposes `redirect: 'manual'`. The test plan covers both paths.
- `react-native-webview` behaviour inside Expo Go (cookie clearing); C54 verifies.

## Not in this chunk
Any real service; camera pictures (C49); clips UI (C53); alerts (C72).

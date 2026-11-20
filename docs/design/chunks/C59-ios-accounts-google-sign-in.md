# C59 — Accounts core and Google sign-in on iOS

| | |
|---|---|
| Phase | 9 — iOS (Expo), Wave B |
| Status | **Design — needs the development build (C58) and a Google Cloud iOS client** |
| Depends on | C58 (custom URL scheme, Info.plist), C48 (`SecretStore`, `secureFetch`) |
| Unblocks | C60 Calendar, C61 Gmail, C62 To-dos, C63 Microsoft, C64/C65 iCloud, C66 Jarvis (an account provider) |
| Runs in | the development build |
| Size | Large |
| Ports | C22 §1, §2, §5 (the account model, Google per account, Settings → Accounts) and C17b (browser sign-in with PKCE), D9, D16, D17, D32 |
| Read first | C22 spec **and** As built (the which-account rule, partial failure, traps), C17b spec **and** As built (PKCE, the refresh-token handling, the weekly expiry), C17 (scopes), `docs/google-setup.md`, 04-lessons L6, L27, L36 |

## Why
Edith's calendar, mail and to-dos read **several accounts at once** (D17): any number of Google accounts, plus Microsoft and
iCloud. Android grew this in C17, C17b and C22. This chunk builds the **provider-neutral account layer** (so C60–C65 only add
sources) and the **Google sign-in** for iOS. On iOS the right way is Google's **iOS OAuth client type** with a reversed-client-ID
custom URL scheme redirect, opened in the system's authentication session (`ASWebAuthenticationSession`, which is the real
browser engine, so Google's "no embedded WebView" rule is met) with **PKCE and no client secret**. A custom scheme must be
registered in Info.plist: **this is why it needs the development build** (decision D28).

## Before you start — owner setup (write it as `docs/google-setup.md` §iOS; verify wording against Google Cloud today)
1. Google Cloud Console → project **Edith** (D9) → **Google Auth Platform → Clients → Create client → iOS**; enter the **bundle identifier** (D34). Copy the **client ID** (`<number>-<hash>.apps.googleusercontent.com`). It is not secret.
2. Keep the consent screen in **Testing** with every account Edith will connect added as a **test user** (D32; the limit is 100). Refresh tokens then expire after **7 days**: expect a browser sign-in about weekly per account (D16, accepted).
3. Enable the APIs: Calendar (C60), Gmail (C61), Tasks (C62) — if not already enabled for Android.
4. Give the client ID to the agent **through EAS environment variables, not the repo**: `eas env:create --name EDITH_GOOGLE_IOS_CLIENT_ID --value … --visibility plain-text --environment development` (the owner runs it). For local `expo start` use an untracked `ios-app/.env.local`.
5. Decisions to record: **D28** (accounts wait for the development build), **D32** (Testing + weekly sign-in).

## Specification

### 1. Configuration at build time (`app.config.ts`, replacing static `app.json` where it needs env)
`ios-app/app.config.ts` extends `app.json` and adds, when `EDITH_GOOGLE_IOS_CLIENT_ID` is set: `ios.infoPlist.CFBundleURLTypes` with the scheme `com.googleusercontent.apps.<the client id's number-hash part, reversed form Google gives>` and `extra.googleIosClientId`. When it is not set: Google shows "Not set up" and the rest of the app works. **Never commit the client ID.** (Google calls the scheme the "reversed client ID": `com.googleusercontent.apps.<NUMBER-HASH>` = the client ID without `.apps.googleusercontent.com`, reversed segments as Google documents; copy the rule from Google's iOS guide, don't guess.) Adding a URL scheme is a **native change: needs a new build** (C58 rules).

### 2. Accounts (`accounts/`)
```ts
type Provider = 'google' | 'microsoft' | 'icloud' | 'jarvis';           // microsoft C63, icloud C64, jarvis C66
interface Account { id: string; provider: Provider; address: string; label: string }   // id random, stable, never the address
type AccountState = {kind:'checking'} | {kind:'connected'} | {kind:'needs_sign_in'; reason: string} | {kind:'error'; message: string};
```
- `AccountStore`: the list in the key-value store (`accounts`, JSON), in the order added.
- `Accounts` (in `Services`): `list: Store<{account, state}[]>`, `add`, `rename(id, label)` (labels unique, case-insensitive; default = the address), `disconnect(id)`, `byName(text)` → `none | one | several` matching a label or address case-insensitively or a **unique prefix** ("work" → "Work (Contoso)"). No fuzzier matching.
- Secrets: `account.<id>.refresh_token` (`SecretStore`). Logs use the account `id`, never the address or label.
- **Sources** (interfaces only here; C60–C65 implement): `CalendarSource`, `MailSource`, `TaskSource` with the shapes in C22 §3 translated to TypeScript (`MailQuery { from, to, subject, text, unreadOnly, after, before }`; results carry `accountId` and the provider's own ids; events carry `iCalUid`). Declare `Outcome<T> = {ok:true, value:T} | {ok:false, kind:'needs_sign_in'|'error'|'timeout', message:string}` (`accounts/outcome.ts`).
- **`Reads`** (`accounts/reads.ts`): run a function on every source **in parallel, each with its own 10 s timeout** (Jarvis sources `Patient`: 45 s, C66), collect values and per-account failures; the tools append the failure line **"Couldn't read work (sam@contoso.com): it needs signing in again — Settings → Accounts."** and the model must say so (L6).
- **`whichAccount`** (`accounts/whichAccount.ts`, pure, **in code, not left to the model**): the four-step rule of C22 §4 (explicit `account`; a `calendar` only one account has; only one account can do it; otherwise return the sentence "Ask the owner which account to use: personal (a@gmail.com) or work (sam@contoso.com). Then call this tool again with `account`." with **no card and no request**). Test every branch.
- **Prompt section** `accountsSection` (replaces nothing): "Accounts: personal — a@gmail.com (Google: calendar and email); work — … (Google: needs signing in again)." Tools in C60–C62 are offered while **at least one** account is connected (`Tool.enabled`).
- Handles: `e1`/`m1`/`t1` are one sequence for the whole app run (not per account), so a handle is never ambiguous; a handle knows its account and calendar.

### 3. Google sign-in per account (`google/`)
- `GoogleAuth` for an account: **authorize** → `https://accounts.google.com/o/oauth2/v2/auth` with `client_id`, `redirect_uri = <reversed scheme>:/oauth2redirect`, `response_type=code`, `scope`, PKCE `code_challenge` (S256; random verifier of 64+ chars, use `expo-crypto`), `state` (random, checked on return), `access_type=offline`, `prompt=consent` (so a refresh token always comes) and, for **adding** an account, `prompt=select_account consent` with no `login_hint`; for **signing in again** `login_hint=<address>`.
- Open with `WebBrowser.openAuthSessionAsync(url, redirectUri, { preferEphemeralSession: false })`. On `type: 'success'` parse `code` and `state` from the URL; `cancel`/`dismiss` → no change. (If `ASWebAuthenticationSession` shows its "wants to use google.com to sign in" sheet, that is iOS and expected.)
- **Token exchange** `POST https://oauth2.googleapis.com/token` (form-encoded: `code`, `client_id`, `code_verifier`, `redirect_uri`, `grant_type=authorization_code`) through `secureFetch` (allowed hosts `googleapis.com`, `google.com`). **No client secret** for an iOS client. Response → `access_token`, `expires_in`, `refresh_token`, `id_token`, `scope`.
- **Email from the `id_token`**: base64url-decode the payload (the token came straight from Google over TLS; no signature check is needed to read the email, but check `aud` equals the client ID and `email_verified`). `email` becomes `Account.address`. If an account with that address exists, **replace its token** (no duplicates).
- **Access tokens** cached in memory per account until 60 s before `expires_in`; **refresh** with `grant_type=refresh_token` (Google does **not** rotate the refresh token; keep the stored one unless a new one is returned). A per-account **mutex**: one refresh in flight; callers await it. `invalid_grant` → `needs_sign_in` with reason "Google asks you to sign in again (about once a week while Edith's Google project is in testing)." A network error is `error`, **not** a sign-out.
- `authorized(scopes, call)`: gets a token, calls, on **401** refreshes once and retries once, on **403 insufficientPermissions/scope** → `needs_sign_in` with reason "Edith needs permission to …: tap Grant access". Scopes are **asked for on demand**: sign-in requests the base set `openid email profile calendar.events calendar.calendarlist.readonly gmail.readonly gmail.compose` and **Tasks (`https://www.googleapis.com/auth/tasks`) only when C62 needs it** (an incremental grant: `include_granted_scopes=true`), as Android's C35 does.
- `disconnect`: `POST https://oauth2.googleapis.com/revoke?token=…`; delete the secret; remove the account.
- **While the sign-in sheet is open** the app is not suspended (ASWebAuthenticationSession is in-process); but if the app is killed meanwhile, the pending PKCE state is lost: the next launch simply has no new account. No pending-state persistence is needed (unlike Android's loopback flow).

### 4. Settings → Accounts (`ui/AccountsSettings.tsx`, a new section above Home)
One row per account: provider icon, label, address, state text. Tap → sheet: **Rename**, **Grant access / Sign in again** (when needed), **Disconnect** (confirm: "Disconnect work? Edith won't see its email or calendar."). **Add account** → a sheet listing providers whose chunks are done (Google here; Microsoft C63, iCloud C64, Work via Jarvis C66). "Not set up" text per provider, e.g. Google without a client ID. The **Calendar** and **Mail** features show "Connect an account" until one exists.

## Files
New: `accounts/{accounts,store,outcome,reads,whichAccount,sources}.ts`, `google/{auth,pkce,idToken,config}.ts`, `ui/AccountsSettings.tsx`, `app.config.ts`, tests for each. Changed: `core/services.tsx`, `ui/SettingsScreen.tsx`, `docs/google-setup.md`, `package.json` (`expo-web-browser`, `expo-crypto`, `expo-auth-session` is **not** needed: write the small PKCE code ourselves, it is ~40 lines and testable).

## Tests
PKCE (verifier length/charset, the S256 challenge against RFC 7636's example vector); authorize URL (every parameter; `select_account` vs `login_hint`); the redirect parser (success, error, wrong `state` rejected, cancel); token exchange against a pretend Google (form body checked, no client secret, `aud` check); id_token parsing (padding, unicode in the name, missing email); per-account mutex (6 parallel calls → 1 refresh); `invalid_grant` → needs_sign_in once; network failure ≠ sign-out; 401 → refresh+retry once; 403 scope → grant-access message; revoke on disconnect; `byName` (label, address, prefix, ambiguous, none); unique labels; `Reads` with one slow and one failing source; `whichAccount` every branch; the Accounts UI (rows, sheet, rename, disconnect confirm, Add account).

## Acceptance criteria
- [ ] `scripts/ios-check.sh` green; the development build runs with the new URL scheme.
- [ ] **Owner, on the iPhone:** Settings → Accounts → Add → Google → the system sheet → sign in → the account shows Connected with its email; add a second Google account (the chooser appears); rename one; Disconnect one (it vanishes from Google's "third-party access" page); a restart keeps both; after 7+ days the account shows "sign in again" (record the date).

## Risks and unverified assumptions
- The iOS client type + custom scheme path has not been tried with the owner's project. If Google refuses (`redirect_uri_mismatch` / `invalid_request`), compare the scheme with Google's current iOS documentation first.
- `ASWebAuthenticationSession` shares Safari's cookies (nice: already-signed-in accounts). `preferEphemeralSession: true` is the alternative for adding a **different** account without the existing session interfering.
- Refresh tokens in Testing expire weekly (D16/D32); the owner accepts it.

## Not in this chunk
Calendar, mail, to-do tools (C60–C62), other providers (C63–C66), Android's Play-services path (no equivalent on iOS), `AccountMigration` (this is a new app: nothing to migrate).

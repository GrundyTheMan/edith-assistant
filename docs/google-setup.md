# Setting up Google for Edith

Edith signs in with your Google account and asks for access to Calendar and Gmail (read, drafts, and sending — she
sends only after you confirm the whole email on screen, decision D5). Google needs a Cloud project that says which
app is asking. This takes about 15 minutes in the
browser; nothing is installed and nothing secret goes into the repo.

On a phone with Google Play services, Edith stores no Google tokens: Play services keeps them. Without Play services
(the iPhone, where Edith runs in Andarium), she signs in through the browser and keeps a **refresh token** on the
device, encrypted with a key that never leaves the phone (decision D16, section 6 below).

You'll need these two fingerprints (public, not secret):
- **Debug** SHA-1 (builds from this PC): `53:5E:82:8A:35:5E:33:32:68:D5:0F:9E:67:F6:91:F1:7A:FF:B4:44`
- **Release** SHA-1 (`scripts/release.sh`): `D1:F6:25:27:35:0F:35:5D:25:42:A1:88:F5:C4:ED:3C:D6:9A:CE:DE`

Google renames Console pages from time to time; if a step doesn't match, the idea is the same — tell me what you
see and I'll help.

## 1. Create the project
1. Open <https://console.cloud.google.com/> and sign in with the Google account Edith should use.
2. Project picker (top bar) → **New project** → name **Edith** → **Create**. Make sure it's selected afterwards.

## 2. Turn on the two APIs
1. **APIs & Services → Library**.
2. Search **Google Calendar API** → **Enable**.
3. Back to the Library, search **Gmail API** → **Enable**.

## 3. Consent screen (Google Auth Platform)
Menu → **APIs & Services → OAuth consent screen** (it may open **Google Auth Platform**). If it asks you to
**Get started**:
1. **App information**: app name **Edith**, user support email: your address.
2. **Audience**: **External**.
3. **Contact information**: your address. Agree to the policy → **Create**.

Then, in Google Auth Platform:
4. **Audience** → leave publishing status as **Testing** → **Test users → Add users** → your Gmail address → **Save**.
5. **Data Access** → **Add or remove scopes** → tick (search for them if needed):
   - `.../auth/calendar.events`
   - `.../auth/calendar.calendarlist.readonly` (C18: which calendars you have checked)
   - `.../auth/gmail.readonly`
   - `.../auth/gmail.compose`
   - `openid` and `.../auth/userinfo.email`

   → **Update** → **Save**. Google marks the Gmail ones as restricted; that's fine for a Testing app used only by you.

## 4. Create three OAuth clients
**Clients** (in Google Auth Platform, or **APIs & Services → Credentials → Create credentials → OAuth client ID**):

1. **Android** — name **Edith debug**, package name **`com.edith.app.debug`**, SHA-1 = the **debug** fingerprint above → **Create**.
   (Debug builds have their own package: `com.edith.app` with this SHA-1 was already registered somewhere.)
2. **Android** — name **Edith release**, package name `com.edith.app`, SHA-1 = the **release** fingerprint above → **Create**.
3. **Web application** — name **Edith web** (no redirect URIs needed) → **Create**. Copy its **Client ID** (it ends
   in `.apps.googleusercontent.com`). You don't need its client secret — leave it in the Console.

## 5. Give Edith the Web client ID
Open `D:\Dev Projects\edith\local.properties` (it's not committed) and add a line:
```
edith.googleWebClientId=PASTE-THE-WEB-CLIENT-ID-HERE.apps.googleusercontent.com
```
Then tell me it's there. The ID isn't a secret (it's in every app that uses it), but keeping it out of the repo
means it isn't in the history either.

## 6. Browser sign-in (for phones without Google Play services; C17b)
Andarium on the iPhone has no Google Play services, so Edith signs in there the way desktop apps do: Google's page
in Chrome, then back to Edith. That needs a fourth OAuth client, of type **Desktop app**.

1. Make sure you're in the **same project** as the other three clients (the project picker at the top; the
   client list shows Edith debug, Edith release and Edith web). A Desktop client in another project gets "Access
   blocked: Edith has not completed the Google verification process": that project's test users don't include
   you. Google Auth Platform → **Clients** → **Create client**.
2. Application type **Desktop app**, name **Edith browser sign-in** → **Create**.
3. Google shows a **Client ID** and a **client secret**. For Desktop clients Google says the secret isn't
   confidential, but treat it like one anyway: don't commit it, and don't paste it into a chat. Add both lines to
   `D:\Dev Projects\edith\local.properties`:
   ```
   edith.googleDesktopClientId=PASTE-THE-DESKTOP-CLIENT-ID-HERE.apps.googleusercontent.com
   edith.googleDesktopClientSecret=PASTE-THE-SECRET-HERE
   ```
   Then tell me they're there. Google's token endpoint **needs** the secret for a Desktop client ("client_secret
   is missing" without it, checked 2026-11-11), so release builds contain it. That's accepted for a Desktop client.
   Google shows a new secret in full only once: copy it from the creation dialog (or **Download JSON**). If it's
   masked later, open the client and use **Add secret**.
4. Nothing else changes: same project, same consent screen, same scopes, same test user.

What to expect:
- **Settings → Accounts → Add account → Google, using a browser instead** (on a phone with Play services), or **Google**
  (without it), opens Google's page in the browser. Sign in, allow access, and the browser comes back to Edith. If
  it ends on a page that won't load, copy its address (it starts with `http://127.0.0.1`) and paste it into Edith.
- While the project is in **Testing**, Google lets this sign-in last **7 days**. After that, Settings shows **Grant
  access**, and one browser round signs Edith back in.

## Good to know
- **Testing mode** lets only the test users (you) sign in. Google may ask you to approve access again from time to
  time (for Testing apps, roughly weekly); Edith shows **Grant access** when that happens.
- You can see and remove Edith's access any time at <https://myaccount.google.com/permissions>.
- The emulator needs to be signed in to a Google account (Settings → Google) for sign-in to work there.

## 7. More than one Google account (C22)
Edith can use any number of Google accounts at once (Settings → **Accounts** → **Add account** → **Google**).
Nothing new is needed in the Console except this: while the project is in **Testing**, Google only lets in accounts
that are on its test-user list. Each extra account must be added first, or Google shows "Access blocked: Edith has
not completed the Google verification process" (Error 403: access_denied).

1. Google Cloud Console → project **Edith** → **Google Auth Platform → Audience → Test users → Add users**.
2. Enter the extra account's address → **Save**. (The limit is 100 test users.) It can take a minute to apply.
3. In Edith: **Add account → Google**. An account that's signed in to Android can use Play services; any other
   account uses **Google, using a browser instead**, where Google shows its account chooser (**Use another account**).

No new client and no new scopes. Each account signed in through the browser has its own weekly sign-in while the
project is in Testing; Settings → Accounts shows **Sign in again** on the one that needs it.

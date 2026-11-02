# Setting up Google for Edith

Edith signs in with your Google account and asks for access to Calendar and Gmail (read, and create drafts — she
never sends). Google needs a Cloud project that says which app is asking. This takes about 15 minutes in the
browser; nothing is installed and nothing secret goes into the repo.

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

## Good to know
- **Testing mode** lets only the test users (you) sign in. Google may ask you to approve access again from time to
  time (for Testing apps, roughly weekly); Edith shows **Grant access** when that happens.
- You can see and remove Edith's access any time at <https://myaccount.google.com/permissions>.
- The emulator needs to be signed in to a Google account (Settings → Google) for sign-in to work there.

# Setting up Microsoft for Edith (C23)

Edith can read and write Outlook mail and calendars (Outlook.com / Hotmail / Live, and Microsoft 365 work or school
accounts: decision D18). Microsoft needs an **app registration** that says which app is asking. It takes about 15
minutes in the browser; nothing is installed, and there's **no secret**: Edith is a "public client" and signs in the
way desktop apps do (Microsoft's page in Chrome, PKCE, the code back on `localhost`).

Microsoft renames portal pages from time to time; if a step doesn't match, the idea is the same — tell me what you
see and I'll help.

## 1. Register the app
1. Open <https://entra.microsoft.com> and sign in with your Microsoft account.
   - If the portal says your account can't register apps (personal accounts without an Entra directory are
     sometimes refused), tell me what it says. The usual free way round is a free Azure account, which gives you a
     directory to register apps in.
2. **Applications → App registrations → New registration** (in the Azure portal it's **Microsoft Entra ID → App
   registrations**).
3. **Name**: `Edith`.
4. **Supported account types**: *Accounts in any organizational directory (Any Microsoft Entra ID tenant –
   Multitenant) and personal Microsoft accounts (e.g. Skype, Xbox)*. (D18: personal and work.)
5. **Redirect URI**: platform **Public client/native (mobile & desktop)**, value `http://localhost` (exactly that,
   no port: Microsoft ignores the port for `localhost`, so Edith can use any free port).
6. **Register**.

## 2. Permissions
**API permissions → Add a permission → Microsoft Graph → Delegated permissions**, tick:
- `offline_access`, `openid`, `email` (under OpenId permissions), `User.Read`
- `Mail.ReadWrite` (drafts need write; read-only isn't enough)
- `Mail.Send`
- `Calendars.ReadWrite`

→ **Add permissions**. You don't need "Grant admin consent" for a personal account: you approve them yourself when
Edith signs in.

## 3. Give Edith the client ID
**Overview** → copy the **Application (client) ID** (a GUID). Open `D:\Dev Projects\edith\local.properties` (not
committed) and add a line:
```
edith.microsoftClientId=PASTE-THE-APPLICATION-CLIENT-ID-HERE
```
Then tell me it's there. Don't create a client secret: Edith doesn't use one.

## What to expect
- **Settings → Accounts → Add account → Microsoft** opens Microsoft's sign-in page in Chrome. Pick or type your
  account, approve the permissions, and Chrome comes back to Edith. If it ends on a page that won't load, copy its
  address (it starts with `http://localhost`) and paste it into Edith.
- Microsoft's sign-in lasts **90 days** without use, and each use extends it, so there's no weekly sign-in like
  Google's while it's in Testing. Changing your Microsoft password signs Edith out; Settings → Accounts then shows
  **Sign in again**.
- A **work or school** account may end with "Need admin approval": your organisation lets only its IT admin allow
  apps. Edith says so; only the admin can change it.
- To remove Edith's access completely: <https://account.live.com/consent/Manage> (personal accounts) or
  <https://myapplications.microsoft.com> (work or school). Disconnect in Edith deletes her token on the phone;
  Microsoft has no way for an app to revoke itself.

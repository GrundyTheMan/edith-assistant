# Setting up iCloud for Edith (C24)

Edith reads and writes iCloud Mail and iCloud Calendar (decision D17). Apple has no sign-in page for other apps to
reach iCloud Mail or Calendar, so Edith uses an **app-specific password** over the standard protocols (IMAP and SMTP
for mail, CalDAV for the calendar). It takes about 5 minutes; nothing is set up in a developer portal.

## 1. Make an app-specific password
1. Open <https://account.apple.com> and sign in with your Apple Account. (App-specific passwords need two-factor
   authentication, which you have.)
2. **Sign-In and Security → App-Specific Passwords → +** (or **Generate an app-specific password**).
3. Name it **Edith** and copy the password Apple shows (it looks like `abcd-efgh-ijkl-mnop`). Apple shows it once.

## 2. Add it in Edith
**Settings → Accounts → Add account → iCloud**: type your iCloud email address (`@icloud.com`, `@me.com` or
`@mac.com`), paste the password, tap **Add**. Edith checks the mail login and the calendar, then saves the password
encrypted on the phone.

Then tap the account in Settings → Accounts and check **Default calendar**: iCloud's own default can be a different
calendar from the one your iPhone uses (the iPhone's choice stays on the phone), so pick the one new events should
go to.

**Type or paste the password into Edith yourself.** Never paste it into a chat, a file or a message: like your
Apple Account password, it opens all of the account's mail, calendars and contacts.

## What to expect
- It keeps working until you revoke it, or until you change your Apple Account password (which revokes every
  app-specific password). Then Settings → Accounts shows **Sign in again**: make a new one and paste it.
- If only mail works, Edith adds the account for email only and says so.
- Edith never marks your mail as read when she searches or reads it.
- **Disconnect** deletes the password from the phone. To stop it working everywhere, revoke **Edith** at
  <https://account.apple.com> → App-Specific Passwords.

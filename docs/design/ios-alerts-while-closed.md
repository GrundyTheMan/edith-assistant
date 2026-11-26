# Camera alerts while Edith is closed (iPhone), through Jarvis

**Status: proposed, 2026-11-26. Nothing in this document is built.** Option B of C72, redone for the shared Kotlin app (the C72 text was written for the Expo app). Jarvis's half is
[`jarvis-push-spec.md`](jarvis-push-spec.md). Decision D31 (A now, B if wanted) is superseded: the owner asked for B on 2026-11-26.

## Why
K4.16 gives the iPhone camera alerts **while Edith is open** and a "While you were away" summary when she is opened again. iOS suspends an app seconds after it leaves the screen, so it cannot poll Ring and Blink
(Android's foreground service has no iPhone equivalent). Jarvis on the PC is always on, so it sends an **Apple push (APNs)** to the phone. Away from home too, as long as the PC and Jarvis are on and online. No PC, no alerts: say so in Settings.

## Parts
| Part | Where | What |
|---|---|---|
| Push permission and token | Swift (`iosApp/`) | `UNUserNotificationCenter.requestAuthorization` when the owner switches the alerts on; `UIApplication.registerForRemoteNotifications()`; `didRegisterForRemoteNotificationsWithDeviceToken` turns the token into hex and hands it to Kotlin. The `aps-environment` entitlement in `iosApp/` (`project.yml` `entitlements:`). A failure to register is reported to Kotlin as text. |
| The seam | `ui/iosMain` | `SwiftPushSeam` (callbacks, as `RtcSeams.kt`): `requestPermission(result)`, `onToken(listener)`, `onTap(listener)`; Swift implements the first, Kotlin gets the rest. `MainViewController(rtc:, push:)`. |
| Registration | `shared/commonMain` `alerts/push/` | `PushRegistration`: registers the token and the **kinds switched on** with Jarvis (`POST /v1/push/register`), at launch, when the token changes and when the choices change; unregisters when the owner switches all off or removes the Jarvis connection; reads `GET /v1/push/status`. Pure logic over `JarvisApi` and a `PushSeam`: tested on the desktop with fakes. |
| No doubles | Swift `willPresent` + Kotlin | While Edith is open she shows her own alert (K4.16). A push for the same `key` that arrives in the foreground is not shown (`willPresent` asks Kotlin: "already shown?"). Pushes for events she did not show (alerts switched off) are not shown either: the kinds list already keeps Jarvis from sending them. |
| The tap | Swift + Kotlin | Tapping a push opens Edith on the Home tab's **clips list** (events carry no clip id; the newest is on top), as the local alerts do. |
| Settings | `ui/commonMain` | In Settings → Alerts: **"Also when Edith is closed (through Jarvis)"** with a status line: "Registered with Jarvis" / "Jarvis can't be reached" / "Jarvis has no push key yet" / "Notifications are off for Edith: allow them in iPhone Settings" / "Connect Jarvis first (Settings → Accounts)". Off by default. The existing words "iPhone doesn't let apps watch in the background" gain "…except through Jarvis, which must be on". |
| Picture in the push | not in the first version | A picture needs a **Notification Service Extension** with Ring/Blink access (a shared Keychain group): large, and the payload must not carry a picture. v1 is text; the tap opens the clips. Decide later. |

## Build and signing facts to check, not assume
- The App ID `com.edith.app.spike` needs the **Push Notifications** capability. Automatic signing (`-allowProvisioningUpdates` with the App Store Connect key) normally switches it on when the entitlement is in the project; if the archive says no profile has it, the owner enables it once on developer.apple.com.
- TestFlight and App Store builds use APNs **production**; only a build run from Xcode uses **sandbox**. Edith sends `env` with the token (`production` for the archive: a build setting passed by the workflows, default `sandbox`), and a mismatch is the classic "no push ever arrives".
- Push does not work in the simulator workflow (no token) and cannot be unit-tested end to end: the first real check is the owner's phone with Jarvis's **Send test push** button.

## Owner steps
1. Create the APNs key and give it to Jarvis (its Settings, never to an agent): see `jarvis-push-spec.md` §9.
2. Install the build, switch the alerts on, allow notifications, press **Send test push** in Jarvis.
3. Ring the doorbell (or the Demo doorbell) with Edith closed.

## Stages
1. **Jarvis side** (`jarvis-push-spec.md`): its own PR in the Jarvis repository. Owner-approved work for the Jarvis agent.
2. **Edith, shared logic**: `PushRegistration`, the Jarvis calls, the Settings row and status, fakes and tests (desktop). *Size S.*
3. **Edith, Swift and entitlement**: the seam, permission, token, foreground suppression and tap; the workflow passes the environment. *Size S; compile-checked only.*
4. **Phone check** (the owner), then fix what it shows.

## Tests
`PushRegistration` with fake `JarvisApi` and seam: registers when the token arrives and the Jarvis connection exists; re-registers when the token or the kinds change; not twice for the same state; unregisters when switched off or Jarvis is
removed; permission refused leaves it off with the message; Jarvis unreachable shows the status and retries at the next launch; the status line for each case. The foreground-suppression rule (a key already shown). Mutation-check each rule as the project does.

## Not verified, and it matters
Everything on a device: that Apple delivers to this app id with the key, the capability and the environment as designed; that a doorbell arrives on the lock screen as time-sensitive; that the tap lands on the clips list; that the suppression stops a double
alert; how soon after the doorbell it arrives (Jarvis's own polling time is added). Whether Jarvis's watchers expose a hook to send from is for the Jarvis agent to confirm (`jarvis-push-spec.md` §1).

## Not in this design
Critical alerts (a special Apple entitlement); pictures in the push; alerts for other devices (locks, temperatures); alerts without Jarvis (Ring's own cloud push needs a Firebase client Apple does not give an app like this); Android (it keeps its foreground service).

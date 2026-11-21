# Putting the spike on your iPhone (owner steps)

The cloud Mac can sign the app and send it to TestFlight, so you install it from the TestFlight app. **I never see your Apple credentials:**
you type them into Apple's and GitHub's own pages. Nothing here is verified yet (it has not run): treat each step as "check what Apple shows".

1. **The bundle identifier is decided** (D43): use `com.edith.app.spike` for this test (the real app will use `com.edith.app`, matching Android). You make an identifier up; Apple only checks that nobody else has it. Register it in developer.apple.com → Certificates, Identifiers & Profiles → Identifiers → + → App IDs, or let step 2 create it.
2. **Create the app in App Store Connect** (appstoreconnect.apple.com → Apps → +): name "Edith", that bundle identifier, language English. TestFlight needs this record.
3. **Create an App Store Connect API key** (Users and Access → Integrations → App Store Connect API → +). Role **Admin** (the cloud signing has to create a
   distribution certificate and profile for you; a lower role may not be allowed to). Download the `.p8` file **once** (Apple shows it only once).
   Note the **Key ID** and the **Issuer ID** shown on that page.
4. **Add three secrets** in GitHub (repository → Settings → Secrets and variables → Actions → Secrets), typed or pasted by you:
   - `SPIKE_ASC_KEY_ID` — the Key ID
   - `SPIKE_ASC_ISSUER_ID` — the Issuer ID
   - `SPIKE_ASC_KEY_P8` — the whole text of the `.p8` file (including the BEGIN and END lines)
5. **Add two variables** (same page → Variables): `SPIKE_BUNDLE_ID` (= `com.edith.app.spike`) and `SPIKE_APPLE_TEAM_ID` (developer.apple.com → Membership → Team ID).
6. **Run the workflow** "Spike KMP TestFlight" (Actions tab → Run workflow). It archives for a real iPhone, signs it with the key, and uploads it.
7. **Install**: in App Store Connect → TestFlight, add yourself as an **internal tester** (your Apple ID), install **TestFlight** on the iPhone, accept the invite, and
   install the build once Apple has processed it (usually several minutes).

Delete the key (App Store Connect → Integrations) when the spike is over.

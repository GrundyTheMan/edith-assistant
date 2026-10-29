# C01 — Project reset and skeleton

| | |
|---|---|
| Phase | 1 — Foundation and chat |
| Status | **Done** — see "As built" at the end |
| Depends on | nothing (first chunk) |
| Unblocks | everything |
| Size | Small–medium (one session) |
| Read first | 00-product, 01-architecture §1–2, §9–10, 02-standards, 05-environment |

## Goal

Replace the v1 app with a clean, modern, empty Edith app in the same repository: builds, launches on
the emulator, shows the bottom navigation with three placeholder screens. v1 remains reachable through
a git tag.

## Scope

In: archiving v1, deleting v1 code, new Gradle project, manifest basics, theme, icon, navigation shell,
repo hygiene files, new `AGENTS.md`/`CLAUDE.md`, optional CI.
Out: any feature (chat, settings logic, etc.).

## Specification

### 1. Archive v1 (ask the owner before pushing)
1. Confirm `git status` is clean and you're on an up-to-date `master`. If anything is uncommitted, stop
   and ask the owner — it may be another agent's work (04-lessons L18).
2. Tag the current `master` commit: `git tag -a edith-v1 -m "Edith v1, before the rebuild"`.
3. Push the tag only after the owner OKs it: `git push origin edith-v1`.

### 2. Remove v1
On branch `c01-project-skeleton`: delete `app/`, root `build.gradle`, `settings.gradle`,
`gradle.properties`, the Gradle wrapper and the old `AGENTS.md`/`CLAUDE.md`. Keep `docs/design/`,
`.gitignore` (rewrite it, below) and the git history.

### 3. New project (Kotlin DSL, version catalog)
- `settings.gradle.kts`: `rootProject.name = "Edith"`, `include(":app")`, repositories `google()`,
  `mavenCentral()`; `dependencyResolutionManagement` with `RepositoriesMode.FAIL_ON_PROJECT_REPOS`.
- `gradle/libs.versions.toml`: every version used (01-architecture §1). Latest stable at implementation.
- Root `build.gradle.kts`: plugins `apply false` (android application, kotlin android, kotlin compose,
  kotlin serialization, ksp).
- `app/build.gradle.kts`:
  - `namespace = "com.edith.app"`, `applicationId = "com.edith.app"`, `minSdk = 26`,
    `compileSdk = targetSdk = latest stable`, `versionCode = 1`, `versionName = "2.0.0-dev"`.
  - Java 17 compile options + `kotlin { jvmToolchain(17) }`.
  - `buildFeatures { compose = true; buildConfig = true }`.
  - Debug: `applicationIdSuffix` **not** used (keep one id so the emulator setup stays simple).
  - Release: `isMinifyEnabled = true` with R8 + default proguard file (+ keep rules for kotlinx.serialization).
  - Dependencies for this chunk only: core-ktx, activity-compose, lifecycle-runtime-compose,
    lifecycle-viewmodel-compose, Compose BOM (ui, material3, icons-extended, tooling-preview),
    navigation-compose, core-splashscreen; test: junit; androidTest: compose ui-test.
- `gradle.properties`: `org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8`, `android.useAndroidX=true`,
  `android.nonTransitiveRClass=true`, `kotlin.code.style=official`.
- Gradle wrapper: latest stable Gradle compatible with the chosen AGP (`gradle wrapper --gradle-version X`).

### 4. Manifest and resources
- `android:label="Edith"`, adaptive launcher icon (vector foreground, solid background in the theme's
  primary purple), round icon, `android:theme="@style/Theme.Edith.Splash"` → post-splash `Theme.Edith`
  (Material3 DayNight NoActionBar parent).
- `android:allowBackup="false"`, `android:dataExtractionRules="@xml/data_extraction_rules"` and
  `android:fullBackupContent="@xml/backup_rules"` excluding everything (01-architecture §9, lessons L16).
- No permissions yet (later chunks add exactly what they need).
- `MainActivity`: `enableEdgeToEdge()`, `installSplashScreen()`, `setContent { EdithTheme { EdithNavHost() } }`.

### 5. Theme
`ui/theme`: Material 3 light + dark color schemes with a purple primary (v1's look: user bubbles in
`primary`), dynamic color **off** (keeps the approved look consistent). Typography defaults.

### 6. Navigation shell
`ui/navigation/Destinations.kt`: `Chat` (start), `Calendar`, `Settings` with icons
(`Chat`, `CalendarMonth`, `Settings`) and labels. `EdithNavHost`: `Scaffold` with `NavigationBar`;
each destination renders a placeholder (`Text("Chat — coming in C07")` etc.). Use
`Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)` on the content.

### 7. Repo hygiene
- `.gitignore`: `.gradle/`, `build/`, `/app/build/`, `local.properties`, `.idea/`, `*.iml`, `.DS_Store`,
  `captures/`, `*.apk`, `*.aab`.
- `.gitattributes`: `* text=auto`, `gradlew text eol=lf`, `*.sh text eol=lf`, `*.bat text eol=crlf`,
  `*.png binary`, `*.jar binary`.
- Root `AGENTS.md`: 30–60 lines — what Edith is (one paragraph), "Read `docs/design/README.md` first",
  build/test/run commands, the emulator start command, the owner preferences in brief (link 02-standards §4).
- `CLAUDE.md`: `@AGENTS.md`.
- Optional (ask the owner — private-repo Actions minutes): `.github/workflows/ci.yml` running
  `assembleDebug testDebugUnitTest lintDebug` on pull requests with JDK 17.

## Tests
- One trivial unit test (`DestinationsTest`: three destinations, Chat is the start) so the test task runs.
- One Compose UI test: the bottom bar shows Chat, Calendar, Settings and switching changes the placeholder.

## Acceptance criteria
- [ ] Tag `edith-v1` exists locally (and on GitHub once the owner approved the push).
- [ ] No v1 source remains on the branch; `docs/design/` is intact.
- [ ] `gradlew clean assembleDebug testDebugUnitTest lintDebug` succeeds.
- [ ] App installs and launches on `Pixel_9_API_37`; splash then bottom bar with 3 destinations; no crash.
- [ ] Rotation keeps the selected destination.
- [ ] `allowBackup="false"` and backup exclusion rules present.
- [ ] New `AGENTS.md` and `CLAUDE.md` exist.

## Manual verification
Install, launch, tap each destination, rotate the emulator (Ctrl+Left), screenshot for the PR.

## Pitfalls
- Don't delete `docs/`. Don't force-push `master`. Ask before pushing the tag.
- The repo path contains a space: quote paths in scripts.

## As built (2026-10-29) — deviations from the spec above, and why
- **No `kotlin android` plugin.** AGP 9 compiles Kotlin itself; applying `org.jetbrains.kotlin.android` is an
  error. The root declares android application, kotlin compose, kotlin serialization and KSP (`apply false`).
- **No `kotlin { jvmToolchain(17) }`.** It would require a JDK 17 install (the owner's PC has JDK 21). Java 17
  bytecode comes from `compileOptions`.
- **Window theme parent is `android:Theme.Material.Light.NoActionBar`** (night: `android:Theme.Material.NoActionBar`),
  not Material3 DayNight, which would add the Material Components (Views) library just for the window background.
  Compose's `EdithTheme` is Material 3.
- **App version in `gradle.properties`** (`edith.versionCode`, `edith.versionName`) so the release scripts can read it.
- **Espresso pinned to 3.7.0** for instrumented tests: Compose ui-test pulls Espresso 3.5, which fails on API 37.
- **Added beyond the spec** (owner request "solidify build and deploy"): release signing with a key outside the
  repo, `scripts/` (check, emulator, run, release, publish, create-signing-key), the `Edith_Release_Test` AVD, and
  CI (`.github/workflows/ci.yml`). All documented in `05-environment.md` §2 and §6.
- Gradle 9.8.2, AGP 9.5.0, Kotlin 2.4.21, compileSdk/targetSdk 37, Compose BOM 2026.09.00.

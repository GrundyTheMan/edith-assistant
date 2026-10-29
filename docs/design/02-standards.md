# 02 — Standards: how every chunk is built, tested and delivered

---

## 1. Definition of done (every chunk)

A chunk is done only when **all** of these are true:

1. The app builds from a clean checkout: `./gradlew clean assembleDebug` (Windows: `gradlew.bat`).
2. All tests pass: `./gradlew testDebugUnitTest` and, if the chunk added any, `connectedDebugAndroidTest`.
3. Lint has no new errors: `./gradlew lintDebug`.
4. The chunk's **Acceptance criteria** are each demonstrated (tests or the manual steps in the chunk),
   with evidence in the PR description (test names, screenshots, log lines, timings).
5. The app was installed and exercised on the emulator (or a phone) for the chunk's feature, with no
   crash and no ANR (`adb logcat -b crash` empty for the app; see `05-environment.md` §4).
6. Documentation updated in the same PR: this design set if the design changed, `AGENTS.md` at the repo
   root if commands or layout changed, and the chunk's status in `README.md`.
7. One pull request per chunk, merged into `master` with a merge commit (§5).

## 2. Code standards

- Match the surrounding code's idioms, naming and comment density. Comments explain *why*, not *what*.
- Kotlin style: official (`kotlin.code.style=official`). No wildcard imports. No `!!` outside tests
  (use `requireNotNull` with a message, or handle null).
- Coroutines: structured concurrency only; no `GlobalScope`; inject `AppDispatchers`; cancellation must
  propagate (rethrow `CancellationException`; never catch it in a generic `catch (e: Exception)` without rethrowing).
- Compose: stateless composables taking state + lambdas; state hoisted to ViewModels; previews for
  components with non-trivial states; content descriptions on every icon button (tests and accessibility
  rely on them — v1's emulator tests found buttons by these descriptions).
- No hard-coded user-facing strings in logic classes; UI strings in `strings.xml`.
- Every user-visible failure produces a clear message (00-product §4.5). Never `catch (e: Exception) {}`.
- Don't log secrets, message bodies or attachment bytes in release builds (01-architecture §8.2).
- Public classes get a one-line KDoc saying what they're for.

## 3. Testing strategy

| Layer | How | Required for |
|---|---|---|
| Pure logic (prompt building, context building, reply parsing, argument coercion, retention rules, chunking) | JVM unit tests | every chunk with logic |
| Ollama protocol | MockWebServer + **recorded fixtures** from `03-ollama-reference.md` (copy into `app/src/test/resources/ollama/`) | C03, C06, C08 and any tool chunk |
| Room | In-memory database tests; migration tests after v1 ships | C05, C16 |
| ViewModels | coroutines-test + Turbine against fakes | each screen chunk |
| Compose UI | `createComposeRule` tests for key interactions (send, action row, confirmation card) | C07, C08, C15 |
| End to end | Manual script on the emulator per chunk (`adb` recipes in `05-environment.md`), real Ollama | every chunk |
| Live model behaviour | Optional Node/curl probes against the real Ollama (see `03-ollama-reference.md` §8); report results, don't commit model-dependent tests | C06, C08 |

Fixture rule: tests must never depend on a live Ollama, the network or a specific model's wording.
Live checks are for evidence in the PR, not for CI.

Fakes over mocks: provide `FakeOllamaClient`, `FakeClock`, `FakeSettingsRepository`, `InMemorySecretStore`
in `app/src/test/java/com/edith/app/testing/` (created in C02/C03 and reused).

## 4. Owner preferences for working with them

- **Honest, direct feedback.** Don't flatter. If something is a bad idea, say so and why.
- **Surface uncertainty directly.** If you can't verify something (how audio sounds, what a model will
  do, whether an API still behaves as documented), ask the owner plainly instead of burying it.
- **Words:** call something a "fix" only when it repairs genuinely broken code; enhancements and behaviour
  changes are "changes".
- **Crashes are never acceptable** (00-product §4.6). Investigate; add logging if evidence is thin.
- **Ask before outward-facing actions:** pushing, opening/merging PRs, creating repos, changing GitHub
  settings, deleting data, force-pushing. Commit only when asked. Don't touch work you didn't do without
  asking (other agents may have uncommitted work in the checkout — v1 history has examples).
- Prefer recommendations with a clear default; offer multiple-choice questions when a decision is the
  owner's. Explain outcomes in plain language with numbers (timings, counts).
- The owner often tests on the emulator while you work and sends short corrections mid-task. Expect it.

## 5. Git and pull requests

- Repository: `https://github.com/GrundyTheMan/edith` (private). Default branch `master`.
- Branch per chunk: `cNN-short-name` (e.g. `c08-tool-framework`) from up-to-date `master`.
- Commits: imperative subject ≤ 72 chars, body explaining why. End commit messages with the
  attribution line your environment requires (if any).
- PR title: `CNN: <chunk title>`. PR body: what changed, how it was tested (evidence), deviations from
  the design and why, follow-ups. Merge with a merge commit after the owner approves.
- Never commit build output, `local.properties`, secrets, API keys or recordings.
- Line endings: the repo has `.gitattributes` (added in C01) forcing LF for `gradlew` and `*.sh`.

## 6. Reporting back to the owner (end of each chunk)

A short summary: what now works (with evidence), what's not done or not verified, any decisions the owner
needs to make (as a clear question with a recommended default), and the PR link.

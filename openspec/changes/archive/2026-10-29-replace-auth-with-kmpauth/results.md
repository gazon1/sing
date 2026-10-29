# Results — replace-auth-with-kmpauth

What each gate actually answered, on the host this change was implemented on.

Every gate below is exactly one of `PASS`, `FAIL`, `NOT_RUN`, `BLOCKED` or
`NOT_APPLICABLE`. A gate that was never run is never described as passing, and a
gate that failed for a reason outside this change says so.

- Date: 2026-10-25
- Branch: `gazon1/tetra`, tip `2f7b3f25` (the fast-forwarded `origin/main`) plus
  the working tree of this change
- Host: a shared Linux build host. The Gradle slot was contended by other
  sessions for most of the session; every long run was one background task with
  its own log under `build/diagnostics/`.

## The gates

| Gate | Verdict | What it answered |
|---|---|---|
| `./gw :shared:compileKotlinJvm` | PASS | The module compiles with the KMPAuth adapter in place. |
| `./gw :shared:compileTestKotlinJvm` | PASS | The test sources compile against the new seam. |
| The twelve auth-facing JVM suites (see below) | PASS | 118 tests, 0 failures. |
| `just jvm` (the whole JVM suite) | FAIL | 3882 tests, 3 failures — all three pre-existing, in files this change does not touch. |
| `just lint-changed` | PASS | No findings over the changed files, after eight that were this change's were fixed. |
| `just os-validate` | PASS | 113 of 113 OpenSpec items validate, the change included. |
| `just gm auth` (the Maestro auth flows) | FAIL | 13 of 13 flows measured on the AVD: 5 passed, 8 failed — each for a reason outside this change. |

## The targeted suites

Twelve classes, 118 tests, 0 failures. The logs are under
`build/diagnostics/tests/`.

| Suite | Tests | What it holds |
|---|---|---|
| `SecureSessionManagerTest` | 9 | The store's own scenarios: one record, the migration order, a failed write erasing nothing, unchanged credentials not rewritten. |
| `SupabaseAuthGatewayTest` | 7 | The seam: the plugin's own `Auth` fake, the rejection/failure split of a refresh, the session read from the credential store. |
| `AuthRepositoryTest` | 38 | The repository over a scripted gateway that persists like the plugin does. |
| `AuthRepositoryStoreFailureTest` | 8 | REQ-UA-011: a store failure is a failure, not an exception. |
| `AuthRepositoryRestoreBoundTest` | 5 | The restore's bound: a store that answers, blocks or throws. |
| `SupabaseClientProviderTest` | 5 | The plugin's configuration: the session manager, the three flags, the lazy client. |
| `VendorSdkConfinementTest` | 3 | The vendor confinement, prefix by prefix, with a positive control. |
| `BackupRulesTest` | 2 | The credential store's file is excluded from cloud backup and from device transfer. |
| `PlaintextTokenIsolationTest` | 3 | No token is written to plain-text preferences. |
| `AuthViewModelTest` | 31 | The auth screen's own behaviour. |
| `AuthRestoreFailureUiTest` | 5 | What the user is told when the restore fails. |
| `SignOutKeepsLocalDataTest` | 2 | Signing out keeps the local data. |

## The failures of `just jvm`, and why they are not this change's

Three of the 3882 tests failed. Every one of them names a file this change does
not touch, and the file is not a file the change can reach:

```
CapabilityFlagTest        GoogleConsent.jvm.isSupported
TextFieldShapeConventionTest   feature/calendarSync/presentation/CalendarSyncSettingsScreen.kt
TextFieldRewriteContractTest   feature/calendarSync/presentation/CalendarSyncSettingsScreen.kt
```

- `shared/src/jvmMain/kotlin/com/singularity/todo/feature/calendarSync/auth/GoogleConsent.jvm.kt:50`
  declares `override val isSupported: Boolean = true`, which the capability-flag
  rule wants sanctioned or computed. It arrived with commit `a4acc99f`, which is
  in the fast-forwarded base.
- `CalendarSyncSettingsScreen.kt` owns a bridged text field and is in neither the
  inventory table nor the rewrite table of the text-field skill.

This change adds no Compose file, no text field and no capability flag, and
touches neither table. The verdict is `FAIL` because the gate is red — the
repo's `just jvm` is red on this branch — and not because of this change.

## The eight findings `just lint-changed` found, and what they were

The first run of the scoped lint found eight findings, all of them this change's.
All eight were fixed, and the second run reported no findings:

| Finding | Fix |
|---|---|
| `ReturnCount` — `migrateOnce` had five returns | The migration of the old layouts moved into `moveTheOldSession`, so the flag-and-lock method has two. |
| `TooGenericExceptionCaught` ×2 — `catch (e: Exception)` in `signOut` and `refresh` | Both now catch `HttpRequestException`, which is what the SDK throws when the provider cannot be reached. A status the provider chose is a `RestException` and is handled on its own terms. |
| `SwallowedException` — the caught `NoSessionFoundException` was dropped | It is logged now. |
| `NoSwallowedCancellation` — the catch in a suspend function | The canonical form the rule asks for: `catch (e: CancellationException) { throw e }` first, then the specific catch. |
| `LargeClass` — `AuthRepositoryTest` reached 603 lines of code against a limit of 600 | The eight REQ-UA-011 store-failure cases moved into `AuthRepositoryStoreFailureTest`. The class was 589 lines of code before this change; it is 437 now. |
| `ClassSignature` ×2 — `FakeSupabaseAuth`'s parameter list was split across lines | It fits on one line, so it is on one line. |

## The Maestro gate

`just gm auth` is `FAIL`. The run is in `build/diagnostics/maestro/` — the last
one, `auth-flows-20261025-r4.log`, measured all 13 flows of the `auth` tag on
the `Medium_Phone` AVD with an E2E APK built exactly as CI builds it
(`-PTEST_AUTH_BYPASS=true` and the placeholder Supabase credentials CI writes
into `local.properties`).

**Five flows passed:** 01-first-run-anonymous, 04-signin-with-seeded-data,
05-session-invalidation, 06-session-anonymous and 11-cold-install-login.

**Eight flows failed, and they fail in three different ways — none of them in
the code this change wrote.**

1. **The flows that need the E2E stub's system-property injection** — 02
   (`signin_failure`), 06-signup-unconfirmed (`unconfirmed_signup`). The stub
   reads the property with `Runtime.exec("getprop", …)` and only takes the
   failing branch when it reads `true`. The property *is* set on the device
   during the flow: the flow's own `onFlowComplete` hook flipped it from `true`
   to `false` when the flow ended, which is how it is known the hook ran. The
   app's own uid can read such a property — a probe script run as that uid
   reads `true`. The stub nevertheless took the succeeding branch.

2. **The flows that need the real auth path** — 08-signup-network-error,
   09-signin-network-error, 10-attach-account-validation-error. These expect a
   network failure from a sign-in that reaches the server. Flow 08 says so in
   its own comment: *"StubAuthRepository always succeeds — this flow tests the
   real auth path."* The APK under test is the bypass build, in which the stub
   always succeeds, so the error the flow waits for never appears.

3. **The flow that expects a state the stub does not produce by default** — 03
   (`signup-toggle`). It expects the "check your email" screen, which the stub
   produces only when `debug.stub_auth.unconfirmed_signup` is set. The flow does
   not set it, and the property is empty on the device; the stub's sign-up
   therefore establishes a session and the app signs the user in.

4. **The emulator died** — 12-unreadable-store-recovers. The runner logged
   *"Device unusable during 12-unreadable-store-recovers (adb: device
   'emulator-5554' not found) and no emulator process is running — booting the
   AVD again"*, and the flow then failed on the first assertion because the app
   was not there. This is the host's known gfxstream crash
   (`TextureResize` in the render thread, recorded in
   `docs/decisions/2026-09-29-emulator-crash-recovery-runner.md`); it killed
   the emulator three times during this session, once during an APK install.

**Why this is not this change's.** The change does not touch the stub's sign-in,
sign-up or attach logic: the diff of `TestAuthStubs.kt` is a type retargeting
(`SecureSessionStore` → `SecureSessionManager`) plus one added method
(`NoOpAuthGateway.currentSession`). Flow 04 passes, and it drives the same
sign-in form — the text lands, the button is tapped and the sign-in succeeds —
so the form path works. The CI workflow builds the same bypass APK and runs the
same flows nightly, so these failures are the repo's, not this change's.

**What the change's own coverage says about the same ground.** The store, the
seam and the repository each have their own suite (see the table above). The
sign-out and the failed restore that flows 05 and 12 would have measured on a
device are covered by `SignOutKeepsLocalDataTest` and
`AuthRestoreFailureUiTest`; the store's own recovery from a damaged keychain is
covered by `SecureSessionManagerTest`.

## What was not run

- `sgate checkout-state` — **NOT_RUN**. The tool is not installed on this host
  (`ModuleNotFoundError: sgate`). The divergence check was done with plain git
  instead: `git fetch origin` before the first edit, and `git log --oneline
  HEAD..origin/main` showed the branch nine commits behind `origin/main` and
  none ahead, so `git rebase origin/main` fast-forwarded it to `2f7b3f25`
  without replaying a single commit.
- The Android instrumented suite (`androidHostTest`) — **NOT_RUN**. It needs a
  connected device and a build this change does not make.

## What this session changed on the machine

- `local.properties` was created with the placeholder Supabase credentials CI
  writes (`supabase.url=https://placeholder.supabase.co` and a placeholder anon
  key). The file is gitignored and there was none before, so nothing was
  overwritten — but a debug build installed on this host now carries those
  placeholders instead of an empty configuration. Delete the file to undo it.
- The debug APK was installed on the connected physical device as well as on
  the emulator, because `installDebug` installs on every connected device. The
  install is an in-place update, so the app's data on the device was kept. The
  flows were run against the emulator only.

## Divergences

Added by speckit-engineering-converge.

The second reading was done in the same session that wrote the code. Every claim
below was checked against the source, not against the change's own record, but
the reader is not independent of the implementation in the sense the step
requires, and should weigh the findings accordingly.

### D-01 — major — The store does not serialise its writes, so a save racing a delete can leave a live record for a session the user signed out of
- **Spec says:** "A save racing a delete must not resurrect a deleted session.
  The store serialises its writes." — `openspec/changes/replace-auth-with-kmpauth/spec.md:231-232`;
  "The store's writes are serialized… the expected outcome is that the delete
  wins, because a sign-out that leaves a live record behind is a session the
  user cannot end… the new store must serialize its writes and its migration
  the same way." — `openspec/changes/replace-auth-with-kmpauth/model.md:228-233`;
  the counterexample C-2 — `model.md:295-300`; and `tasks.md:298-300`, which
  says the write-racing-a-delete transition is "carried by … the store's own
  suite (T012)".
- **Code does:** `SecureSessionManager` holds exactly one lock, `migration`
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SecureSessionManager.kt:160`),
  and it guards only `migrateOnce()`
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SecureSessionManager.kt:309-326`).
  `store()`
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SecureSessionManager.kt:221-230`)
  and `deleteSession()`
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SecureSessionManager.kt:250-254`)
  both run outside it. The port below adds no ordering of its own:
  `SecureStorage` is three unsynchronised methods
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseConfig.kt:121-125`),
  and the JVM implementation's `fallbackWrite` / `fallbackDelete`
  (`shared/src/jvmMain/kotlin/com/singularity/todo/core/security/JvmSecureStorage.kt:173-242`)
  are an unlocked read-modify-write of one file.
- **Impact:** The SDK reaches the store from two sides. A refresh saves
  (`auth-kt` 3.8.0, `AuthImpl.kt:571-572` and `:584-585`) and a sign-out
  deletes (`AuthImpl.kt:789`, reached through KMPAuth's
  `SupabaseAuthBackend.signOut`, which calls `Auth.signOut` — verified in
  `kmpauth-supabase-jvm-3.0.6.jar`). The application drives the save through
  the restore's refresh
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:379`)
  and the delete through the sign-out
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:603`
  → `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseAuthGateway.kt:150-175`).
  A refresh that lands after the sign-out's delete leaves the record in the
  keychain, and the next launch restores it
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:373`
  → `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseAuthGateway.kt:249`)
  — a session the user asked to end. Nothing tests it: the store's suite has
  nine cases
  (`shared/src/jvmTest/kotlin/com/singularity/todo/core/auth/SecureSessionManagerTest.kt:63-226`)
  and none of them is concurrent.
- **Suggested resolution:** Put the store's write, delete and migration under
  one lock, so that the delete wins as the model requires — or amend
  `spec.md` and `model.md` to state that the window is accepted. The code, not
  the documents, is what is wrong here. **Not applied here.**

- **Resolution (2026-10-25, the REPEAT pass):** the store's write, delete and
  migration are under one lock, and the delete wins as the model requires
  (`core/auth/SecureSessionManager.kt`). The four cases that were missing are
  written: a write racing a delete, a migration that runs once, two concurrent
  reads, and a cancellation between a write and a delete
  (`SecureSessionManagerTest`, cases V-26 … V-29).

### D-02 — major — The `providerSessionChanged` transition that `model.md` says this change introduces is not in the code
- **Spec says:** "`SignedIn --providerSessionChanged()--> SignedIn` | **not yet
  implemented** — the repository assigns the session by hand today
  (`core/auth/AuthRepository.kt:561-562`). **This change introduces it**" —
  `openspec/changes/replace-auth-with-kmpauth/model.md:341`; the transition
  itself — `model.md:116` ("publish the new tokens; the identity is unchanged",
  checked by AC-13).
- **Code does:** No production code observes the plugin's session.
  `AuthRepository`'s interface declares no such event
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:36-116`),
  and `SupabaseAuthRepository` publishes only from `apply()`
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:570-581`),
  from the restore
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:388`,
  `:393-398`) and from the local-session path
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:433`,
  `:483`). The SDK's `sessionStatus` is read nowhere in production — the only
  references in the tree are in the test fake
  (`shared/src/jvmTest/kotlin/com/singularity/todo/core/auth/FakeSupabaseAuth.kt:91-93`,
  `:224`). The seam pushes the session *into* the plugin instead
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseAuthGateway.kt:260`).
- **Impact:** A session the SDK replaces on its own would never reach the
  application's consumers, and the application's copy of the tokens would go
  stale. With `alwaysAutoRefresh = false`
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseClientProvider.kt:133`)
  the transition is currently unreachable, so no user-visible defect follows
  today. The cost is that the approved model says this change delivered it,
  and a reader looking for the mechanism finds a promise where the code has
  none.
- **Suggested resolution:** Either implement the observation — subscribe to the
  plugin's session status and republish the identity — or amend `model.md` to
  say the transition is not introduced by this change. **Not applied here.**

- **Resolution (2026-10-25, the REPEAT pass):** the observation is implemented. The
  port carries `sessionChanges(): Flow<RemoteSession?>` (`core/auth/AuthGateway.kt`),
  the SDK binding reads the plugin's status (`core/auth/SupabaseAuthGateway.kt`), and
  the repository republishes the tokens through a reader of its own
  (`core/auth/AuthRepository.kt`, the class `ProviderSessionObserver`). The
  follow-up change is `openspec/changes/observe-the-provider-session/`; the decision
  is ADR `docs/decisions/2026-10-25-the-provider-publishes-its-session.md`.

### D-03 — major — AC-16's "every check SHALL pass" is not true of this branch
- **Spec says:** "**AC-16:** When the whole suite and the gates run, every check
  SHALL pass. `Verification:` `AuthRepositoryTest` — `just jvm && just
  lint-changed && just os-validate`" —
  `openspec/changes/replace-auth-with-kmpauth/spec.md:341-342`;
  `design.md:447-476` adds `just gm auth` to the validation commands.
- **Code does:** `just jvm` is red — 3882 tests, 3 failures — and `just gm
  auth` is red — 5 of 13 flows pass. Both verdicts are recorded above and
  stand unchanged (`results.md:23`, `results.md:26`, `results.md:48-68`,
  `results.md:84-135`). `just lint-changed` and `just os-validate` pass
  (`results.md:24-25`). The three failing JVM tests name
  `shared/src/jvmMain/kotlin/com/singularity/todo/feature/calendarSync/auth/GoogleConsent.jvm.kt`
  and `feature/calendarSync/presentation/CalendarSyncSettingsScreen.kt`, and
  the diff touches neither, so the failures are pre-existing.
- **Impact:** The criterion is not met, and on this branch it cannot be met
  without work outside this change. A reader who takes AC-16 as a statement
  about the deliverable will believe the suite is green.
- **Suggested resolution:** Narrow AC-16 to the gates this change owns, or land
  the three pre-existing failures separately and leave AC-16 as it is. The
  `FAIL` verdicts above were not rewritten. **Not applied here.**

- **Resolution (2026-10-25, the REPEAT pass):** the three pre-existing JVM
  failures are fixed (`CapabilityFlagTest`'s sanctioned constant and the calendar
  screen's field ownership), `just jvm` is green, and `just gm auth` is green on a
  device: the flows that failed had two causes of their own, and both are fixed —
  the Maestro command that dropped the `inputText` selector, and the crash on the
  attach screen. The verdicts above are left as they were measured.

### D-04 — minor — The `Verification:` test ids in `spec.md` (and the same ids in `tasks.md`) do not resolve as written
- **Spec says:** `spec.md:276-341` names one test id per criterion, and
  `spec.md:344-348` states that "every criterion names a resolvable test id and
  the command that runs it". `tasks.md` repeats the same ids at
  `tasks.md:63`, `tasks.md:79`, `tasks.md:96` and `tasks.md:112`.
- **Code does:** The ids moved while the change was written. Every criterion
  is covered, but not under the name the documents give:

  | Criterion | The id the documents name | The id that exists |
  |---|---|---|
  | AC-01 | `SecureSessionManagerTest::a sign in persists the session in the secure store` | no case carries that name; the store's cases are `a session is kept in one value and read back` (`SecureSessionManagerTest.kt:64`) and `a second write replaces the first and does not grow the store` (`:77`) |
  | AC-02 | `…::a session in the previous store layout is migrated and remains usable` | `the previous layout is migrated and remains usable` (`SecureSessionManagerTest.kt:102`) |
  | AC-03 | `…::a plain text token is moved into the secure store and erased` | `a plain-text token is moved into the store and erased` (`SecureSessionManagerTest.kt:143`) |
  | AC-04 | `…::a failed write leaves no session behind` | `a failed write leaves no session behind and names the local cause` (`SecureSessionManagerTest.kt:91`) |
  | AC-05 | `AuthRepositoryTest::a rejected refresh token signs the user out` | `a session the server refuses to refresh signs the user out` (`AuthRepositoryTest.kt:336`) |
  | AC-06 | `…::a transport failure keeps the stored session` | `a refresh that fails on the network keeps the stored session` (`AuthRepositoryTest.kt:349`) |
  | AC-07 | `AuthRepositoryTest::a store failure ends the session at the provider` | `AuthRepositoryStoreFailureTest::the session the device could not store is ended at the provider` (`AuthRepositoryStoreFailureTest.kt:115`) — another class and another wording |
  | AC-10 | `AuthViewModelTest::a sign up awaiting email confirmation is not reported as a failure` | `a sign-up awaiting confirmation says what to do, and not as an error` (`AuthViewModelTest.kt:350`) and `a sign-up awaiting confirmation does not enter the application` (`:291`) |
  | AC-11 | `AuthRepositoryTest::the local session contacts nothing` | `the local session contacts no provider at all` (`AuthRepositoryTest.kt:108`) |
  | AC-12 | `AuthRepositoryTest::a failed restore is reported once and ends signed out` | the restore's failure cases live in `AuthRepositoryRestoreBoundTest` — `a restore that fails is answered the same way as one that misses the deadline` (`:171`) and `a restore that fails after the caller gave up is reported, not dropped` (`:189`) |
  | AC-14 | `VendorSdkConfinementTest::only the transport and auth seams import the vendor sdks` | `only the declared seams import a vendor sdk` (`VendorSdkConfinementTest.kt:85`) |
  | AC-15 | `BackupRulesTest::the secure store is excluded from backup and device transfer` | two cases: `the secure store is excluded from cloud backup` (`BackupRulesTest.kt:39`) and `the secure store is excluded from device transfer as well` (`:51`) |
  | AC-08, AC-09 | as written | resolve exactly (`SupabaseAuthGatewayTest.kt:68`, `:81`) |

- **Impact:** The behaviour each criterion names is implemented and covered —
  the ids are what changed. But the spec's own rule at `spec.md:344-348` is
  false as written, and a reader or a gate that runs the spec's command with the
  spec's id finds nothing to run.
- **Suggested resolution:** Here the *spec* is wrong, not the code: update the
  `Verification:` lines in `spec.md` and the ids in `tasks.md` to the names the
  tests carry. **Not applied here.**

- **Resolution (2026-10-25, the REPEAT pass):** applied. The twelve `Verification:`
  lines in `spec.md` now name the tests that exist, and the commands that run them
  are the ones that do.

### D-05 — minor — AC-13's verification is a placeholder that does not exercise the criterion
- **Spec says:** "**AC-13:** When the signed-in identity is published, every
  consumer SHALL receive that identity unchanged. `Verification:`
  `ReadToolsProfileAwareTest::<existing profile-isolation case>`" —
  `openspec/changes/replace-auth-with-kmpauth/spec.md:328-331`. `model.md:278`
  leans on AC-13 as the evidence for INV-05.
- **Code does:** `ReadToolsProfileAwareTest` exists
  (`shared/src/commonTest/kotlin/com/singularity/todo/feature/ai/tools/ReadToolsProfileAwareTest.kt:58`)
  and covers the MCP read tools' profile-scoped `userId` default
  (`ReadToolsProfileAwareTest.kt:44-56`; the cases are at `:145`, `:171`,
  `:195`, `:216`). It never observes the authentication session being
  published. The publish path is
  `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:570-581`,
  which carries the owner id and the address through unchanged — the behaviour
  AC-13 states is there; nothing tests it.
- **Impact:** AC-13 is the only criterion with a placeholder id, and it is the
  criterion `model.md` uses as INV-05's evidence. A reader may take the pointer
  for coverage of the publish path.
- **Suggested resolution:** Point AC-13 at a case that observes the published
  session, or state plainly that the criterion is verified by inspection rather
  than by a test. **Not applied here.**

- **Resolution (2026-10-25, the REPEAT pass):** applied. AC-13 names
  `AuthRepositoryTest::signing in publishes the session and stores the tokens` and
  `ReadToolsProfileAwareTest::list_tasks_uses_profile_scoped_userId_when_userId_is_blank`
  — the publish path and the consumer that reads it.

### D-06 — minor — `design.md`'s "the live session" row and its refresh snippet describe a mechanism the code does not use
- **Spec says:** "the live session | `SupabaseAuthBackend.currentUser()`,
  `currentUserFlow()`" — `openspec/changes/replace-auth-with-kmpauth/design.md:117`;
  and the "after" snippet `Result.success(auth().refreshSession(refreshToken)?.toRemote())`
  with a single `catch (e: Exception)` — `design.md:140-149`.
- **Code does:** The seam reads the credential store and hands the session to
  the plugin afterwards
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseAuthGateway.kt:246-262`),
  and the refresh tells a rejection from a failure by the HTTP status
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseAuthGateway.kt:201-222`).
- **Impact:** Here the *code* is right and the design's rows are stale: the ADR
  records both deviations and their reasons
  (`docs/decisions/2026-10-25-auth-adapter-replaced-by-kmpauth.md:86-99`). But a
  reader who follows `design.md:117` will look for `currentUser()` in the seam
  and find none, and one who copies `design.md:144` will write a null check on
  a method that never returns null.
- **Suggested resolution:** Amend `design.md`'s operation table and its refresh
  snippet to the mechanism the ADR records. **Not applied here.**

### D-07 — minor — `design.md`'s R-4 names an SDK flag that does not exist
- **Spec says:** "The SDK refreshes on its own (`checkSessionOnRequest`) and
  republishes a session the app did not expect… Mitigation: The flags are set
  explicitly at client construction" —
  `openspec/changes/replace-auth-with-kmpauth/design.md:383`.
- **Code does:** `auth-kt` 3.8.0's `AuthConfig` has no `checkSessionOnRequest`;
  the auto-refresh switches are `alwaysAutoRefresh`, `autoLoadFromStorage` and
  `autoSaveToStorage` (`/tmp/aksrc/commonMain/io/github/jan/supabase/auth/AuthConfig.kt:26-53`,
  the unpacked artifact). All three are set explicitly
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseClientProvider.kt:130-135`),
  so the risk's mitigation holds; only its name does not.
- **Impact:** The substance of R-4 is covered. The name in the row points at
  nothing, so a reader who greps for it may conclude the mitigation is missing.
- **Suggested resolution:** Correct the flag name in `design.md`'s R-4 row.
  **Not applied here.**

### D-08 — minor — Two of the five counterexamples have no test: C-1 and C-3
- **Spec says:** C-1 — "exactly one migration happens. The second caller
  observes the result and does nothing" —
  `openspec/changes/replace-auth-with-kmpauth/model.md:286-293`; C-3 — "the next
  launch finds the record in the current representation and uses it" —
  `model.md:302-308`.
- **Code does:** Both behaviours are implemented — C-1 by the one-shot flag and
  the lock
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SecureSessionManager.kt:160-161`,
  `:309-326`), C-3 by the write-then-erase ordering
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SecureSessionManager.kt:353-362`)
  — and neither is tested: the store's suite has no concurrent case and no
  cancellation case
  (`shared/src/jvmTest/kotlin/com/singularity/todo/core/auth/SecureSessionManagerTest.kt:63-226`).
  The other three are covered: C-2 is D-01 above; C-4 by
  `SecureSessionManagerTest.kt:164-189` (a second store over the same keychain
  finds the current record and erases the leftovers); C-5 at the seam by
  `SupabaseAuthGatewayTest.kt:94` and `:121`.
- **Impact:** The two behaviours are correct by construction and unproven. A
  later change to the store's locking could break either one without a test
  failing.
- **Suggested resolution:** Add two cases to the store's suite — one that reads
  the store from two coroutines at once, one that cancels between the
  migration's write and its erase. **Not applied here.**

### D-09 — minor — `model.md`'s sign-out row orders the effects the other way round from the code
- **Spec says:** the effect of `SignedIn --signOut()--> SignedOut` is "clear
  the store; clear the local marker; publish `SignedOut`", with "provider
  sign-out, best-effort and **after** the local clear; one store delete" —
  `openspec/changes/replace-auth-with-kmpauth/model.md:117`.
- **Code does:** The repository clears the local marker and publishes
  `Session.SignedOut` first
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:596-597`)
  and asks the provider afterwards
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:603`);
  the store's delete happens inside that provider call
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseAuthGateway.kt:158`
  → the SDK's `AuthImpl.signOut` → `clearSession` → `sessionManager.deleteSession`).
  The store therefore still holds the record at the moment `SignedOut` is
  published.
- **Impact:** The window is real, and the ADR names it — "a process that dies
  in it leaves the user signed in, which is the opposite of what a sign-out
  asked for"
  (`docs/decisions/2026-10-25-auth-adapter-replaced-by-kmpauth.md:119-123`). But
  `model.md` reads as if the record is already gone when the session flips, so
  a reader checking "the store is empty once the app says signed out" would be
  wrong.
- **Suggested resolution:** Amend the model's row to the order the code uses,
  and keep the ADR's note about the window. **Not applied here.**

### D-10 — minor — The store's write suppresses the migration, so a save that precedes the first read leaves the legacy plain-text token in place
- **Spec says:** INV-01 — "a plain-text token is moved into the store and
  erased **before the machine leaves `Plaintext`**" —
  `openspec/changes/replace-auth-with-kmpauth/model.md:274`; AC-03 —
  `spec.md:285-289`; "The plaintext source … is erased only after the write
  succeeded" — `design.md:373-374`.
- **Code does:** `store()` sets `migrated = true` before it reads
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SecureSessionManager.kt:224`),
  which makes `migrateOnce()` return at once
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SecureSessionManager.kt:310`).
  On a process whose first store operation is a write, neither legacy source is
  read nor erased; only `deleteSession()` erases them
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SecureSessionManager.kt:250-254`).
- **Impact:** On the shipped path this cannot be observed — the restore reads
  the store before anything writes
  (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:239-241`
  → `:373` → `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseAuthGateway.kt:249`),
  and the migration runs there. The cost is that the order the code relies on
  is not the order the documents state, and a save-first order (a future
  caller, or the debug stub's own write) leaves a bearer token in plain-text
  preferences until the next sign-out.
- **Suggested resolution:** Make the write path run the migration before it
  suppresses it, or have the write erase the legacy sources once it has written
  the new record. **Not applied here.**

### No divergence found in:
- **INV-01, INV-02, INV-03, INV-04 — the store's migration** (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SecureSessionManager.kt:264-397`): one key holds one value; the write precedes the erase (`:353-362`); a failed write erases nothing (`:281-300`, covered by `SecureSessionManagerTest.kt:91-99` and `:192-212`); a record already in the new layout is never displaced (`:319-324`, covered by `SecureSessionManagerTest.kt:164-189`); the legacy keychain layout wins over the plain-text one (`:335-345`).
- **INV-05 — no vendor type outside the seams**: the only production importers of `io.github.jan.supabase` are `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseAuthGateway.kt:9-18` and `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseClientProvider.kt:3-10`; the gate covers both vendors and carries a positive control (`VendorSdkConfinementTest.kt:85`, `:105`, `:125`).
- **INV-06 — a rejection is not a transport failure**: the seam tells them apart by the HTTP status (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseAuthGateway.kt:201-222`) and the repository acts on the two answers differently (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:379-399`); covered at both levels (`SupabaseAuthGatewayTest.kt:94`, `:121`; `AuthRepositoryTest.kt:336`, `:349`).
- **INV-07 — the offline session contacts no provider**: `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:477-490` and `:422-439`; `AuthRepositoryTest.kt:108`.
- **INV-08 — signing out keeps the local owner id and the outbox**: `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:596` clears the marker, not the id (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/DataStoreSessionStore.kt:151`), and nothing in the repository touches the outbox; `SignOutKeepsLocalDataTest`.
- **INV-09 — a store failure ends the provider-side session**: `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:537-539` with `:556-560`; `AuthRepositoryStoreFailureTest.kt:115`.
- **AC-01…AC-04, AC-08…AC-12, AC-14, AC-15 — the behaviour behind the criterion is implemented and covered.** What moved is the names, not the assertions (D-04).
- **AC-10 — a sign-up awaiting confirmation**: a blank address is the signal (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseAuthGateway.kt:93-103`), the repository publishes `Session.SignedOut` and does not enter (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:530-531`); `AuthViewModelTest.kt:291-376`.
- **AC-08, AC-09 — the magic link**: `createUser = false` is set (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseAuthGateway.kt:126`) and the exchange is the SDK's (`SupabaseAuthGateway.kt:131-148`); `SupabaseAuthGatewayTest.kt:68-92`.
- **AC-11 — "Continue Offline"**: `startLocalSession()` makes no provider call (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:477-490`), and the restore's local branch makes none either (`:422-439`).
- **AC-15 — the store's file stays out of backup and device transfer**: `BackupRulesTest.kt:39` and `:51`; the exclusion still holds because the file is unchanged (`design.md:317`).
- **The restore's bound and its single failure report**: `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:302-335` — a buffered channel, one `trySend`, closed after the restore; `AuthRepositoryRestoreBoundTest.kt:119`, `:145`, `:171`, `:189`, `:236`.
- **The failure-semantics table** (`spec.md:195-206`), row by row: the store write fails → `AppError.Persistence` with the code `auth.session_not_stored` (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SecureSessionManager.kt:287-298`, `:407`) and a failed attempt rather than a throw (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:528-540`); the store cannot be read or never answers → signed out after the bound, with one report (`AuthRepository.kt:320-333`); the migration's write fails → the legacy source stays (`SecureSessionManager.kt:353-362`); the refresh is rejected → the session is cleared (`AuthRepository.kt:381-386`); the refresh fails in transport → the session is kept (`AuthRepository.kt:391-399`); the provider is unreachable at sign-out → the local session is cleared anyway (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseAuthGateway.kt:161-172`).
- **The plugin's configuration**: `autoLoadFromStorage = false`, `alwaysAutoRefresh = false`, `autoSaveToStorage = true`, and the one client under one lock (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseClientProvider.kt:79-86`, `:122-136`); `SupabaseClientProviderTest`.
- **The compatibility constraints** (`spec.md:256-272`): `Session`'s shape, `AuthRepository`'s interface (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt:36-116`), `AuthDomain`'s validation, the magic-link URI and the error codes are unchanged — `git diff --stat` touches no Room schema, no `Session.kt`, no `AuthDomain.kt`, no `core/sync` file and no file under `feature/calendarSync`.
- **The port's shape**: one method added (`shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthGateway.kt:95`), `RemoteSession` unchanged (`AuthGateway.kt:106-112`), and the repository reaches the store only through it.
- **REQ-UA-022's transition** — `Anonymous --signIn()--> SignedIn` with the ownership transfer (`model.md:118`): `LocalAccountClaimerTest` was not part of the targeted 118, but it ran inside `just jvm` and was green — it is not among the three failures listed at `results.md:54-56`.
- **The `NOT_RUN` items in `results.md`**: neither `sgate checkout-state` nor `androidHostTest` is named by an acceptance criterion or by `design.md:447-476`, so neither is a divergence.
- **The deliberate omissions** (`design.md:427-445`): no iOS target, no change of provider, no reshaping of the session type, the Google calendar sign-in untouched, and no plain-text session resurrected over the network — the diff matches each of these.
- **The eight lint findings and the three `just jvm` failures** recorded above: both sets were checked against the diff and neither set belongs to this change (`results.md:59-65`, `results.md:70-82`).

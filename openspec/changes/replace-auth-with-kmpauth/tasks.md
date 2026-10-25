# Tasks — Replace the home-grown auth adapter with KMPAuth's Supabase backend

Every task names the test id that verifies it and the acceptance criterion it serves. The
`Verification:` lines use `./gw`, the repository's own Gradle wrapper, because `AGENTS.md`
requires it over `./gradlew` — the two are equivalent, and `spec.md` names the other one.

Phase 1 tasks are marked `[P]`: they touch disjoint files and none of them depends on
another. No two `[P]` tasks touch the same file.

---

## Phase 1 — foundation

- [x] T001 [P] Write the decision record for replacing the auth adapter, and correct the
      coverage index so it no longer claims a gap that has been filled.
  - Files: `docs/decisions/2026-10-25-auth-adapter-replaced-by-kmpauth.md`,
    `openspec/specs/MODULE-INDEX.md`
  - Depends on: none
  - Verification: `VendorSdkConfinementTest::only the transport and auth seams import the vendor sdks`
    — `./gw :shared:jvmTest --tests 'VendorSdkConfinementTest'`
  - Acceptance: AC-16
  - Notes: The ADR records the three operations KMPAuth cannot express and the three
    legitimate alternatives that lost (design.md § A-1 … A-7). `MODULE-INDEX.md`'s
    `core/security`, `core/auth` row is amended to record that the candidate id
    `core/auth-token-storage` was **not** taken, and that the observable token-storage
    contract is owned by `user-authentication` (REQ-UA-002, REQ-UA-012, REQ-UA-013) — the
    delta in `specs/user-authentication/spec.md` amends that capability rather than
    creating a second owner for one behaviour.

- [x] T002 [P] Add KMPAuth's Supabase backend to the version catalogue and to the shared
      module, and settle whether the published backend binds against the SDK version this
      project pins.
  - Files: `gradle/libs.versions.toml`, `shared/build.gradle.kts`
  - Depends on: none
  - Verification: `SupabaseAuthGatewayTest::a magic link token is exchanged for a session`
    — `./gw :shared:jvmTest --tests 'SupabaseAuthGatewayTest'`
  - Acceptance: AC-16
  - Notes: `kmpauth-supabase:3.0.6` is published against `auth-kt:3.7.0`; the project pins
    `supabase = "3.8.0"`. Resolve the dependency, then confirm the resolved `auth-kt` and
    that KMPAuth's calls into the SDK still bind —
    `./gw :shared:dependencyInsight --configuration jvmRuntimeClasspath --dependency auth-kt`.
    **This is R-1 and it is settled before any other work.** If a signature differs, pin
    `auth-kt` to the version KMPAuth was compiled against and re-check the app's own SDK
    usage. The catalogue must use the `version.ref` form — `sgate version_catalog_has_no_literals`
    fails on a literal.

- [x] T003 [P] Add the port's read of the live session, so the repository stops reaching
      into the credential store for the pieces of one.
  - Files: `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthGateway.kt`
  - Depends on: none
  - Verification: `AuthRepositoryTest::a failed restore is reported once and ends signed out`
    — `./gw :shared:jvmTest --tests 'AuthRepositoryTest'`
  - Acceptance: AC-12
  - Notes: One method — `suspend fun currentSession(): RemoteSession?` — with the same KDoc
    shape as its neighbours: it returns the live session in the port's own vocabulary, never
    a vendor type. The method replaces the four separate `sessionStore.current*()` reads in
    the restore path.

- [x] T004 [P] Write the credential store that persists the session as one value, and that
      moves a session out of either legacy source without ever losing it.
  - Files: `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SecureSessionManager.kt`
  - Depends on: none
  - Verification: `SecureSessionManagerTest::a session in the previous store layout is migrated and remains usable`
    — `./gw :shared:jvmTest --tests 'SecureSessionManagerTest'`
  - Acceptance: AC-01, AC-02, AC-03, AC-04
  - Notes: Implements the SDK's `SessionManager` over the app's `SecureStorage` port. One key
    holding one project-level `@Serializable` record — access token, refresh token, owner id,
    email, expiry — with the owner id nullable so a legacy token-only record can be stored
    without pretending to be a session. The migration reads the keychain's previous four
    values first and the plain-text preferences second, writes the new key **first** and
    erases the old source **second**, and a failed write erases nothing. The
    `auth.session_not_stored` error code and its message are carried over unchanged from
    `SecureSessionStore`. The record type is `internal` and lives in this file.

- [x] T011 [P] Write the test that the credential store's file stays excluded from backup
      and from device transfer.
  - Files: `shared/src/jvmTest/kotlin/com/singularity/todo/arch/BackupRulesTest.kt`
  - Depends on: none
  - Verification: `BackupRulesTest::the secure store is excluded from backup and device transfer`
    — `./gw :shared:jvmTest --tests 'BackupRulesTest'`
  - Acceptance: AC-15
  - Notes: Reads `androidApp/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml`
    and asserts the secure store's file is excluded in **both**, in both the cloud-backup
    and the device-transfer sections. No such test exists today, and the store's
    representation changes in this change — the exclusion is `REQ-UA-015`'s, and nothing
    currently checks it.

---

## Phase 2 — the seam's plumbing

- [x] T005 Install the session manager in the provider's auth plugin, and give the seam a
      substitutable way to reach that plugin.
  - Files: `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseClientProvider.kt`
  - Depends on: T004
  - Verification: `SecureSessionManagerTest::a sign in persists the session in the secure store`
    — `./gw :shared:jvmTest --tests 'SecureSessionManagerTest'`
  - Acceptance: AC-02, AC-12
  - Notes: Three changes to one file. Install the `SessionManager` when the `Auth` plugin is
    installed, so the SDK persists through the app's keychain and not through its own
    settings store. Set `autoLoadFromStorage = false` and the other refresh flags
    explicitly — the SDK must not read its session while the client is being built, because
    whoever builds the client first is not necessarily the restore, and the migration must
    stay inside the restore's bound (design.md § "The session load happens inside the
    restore's bound"). Add `open suspend fun auth(): Auth? = client()?.auth` — the class is
    already `open` with `open` methods for exactly this substitution.

- [x] T012 [P] Write the store's tests: the migration from both legacy sources, the atomic
      write, and every way the write can fail.
  - Files: `shared/src/jvmTest/kotlin/com/singularity/todo/core/auth/SecureSessionManagerTest.kt`
  - Depends on: T004
  - Verification: `SecureSessionManagerTest::a plain text token is moved into the secure store and erased`
    — `./gw :shared:jvmTest --tests 'SecureSessionManagerTest'`
  - Acceptance: AC-01, AC-02, AC-03, AC-04
  - Notes: Carries over the four subjects of the `SecureSessionStoreTest` it replaces —
    the migration, the write, the partial write and the failure — and adds the migration
    from the **previous keychain layout**, which is the new and the load-bearing case
    (REQ-UA-023). Reuses `MapSecureStorage`, including `failWriteNumber`, which is the only
    way to tell an all-or-nothing caller from one that undoes a partial write. Carries the
    scenarios V-01 … V-08 and V-27 … V-29.

---

## Phase 3 — the seam and the repository

- [x] T006 Rewrite the vendor seam over KMPAuth's Supabase backend, keep the three
      operations KMPAuth cannot express as direct SDK calls, and make the refresh able to
      say "rejected".
  - Files: `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SupabaseAuthGateway.kt`
  - Depends on: T002, T003, T005
  - Verification: `SupabaseAuthGatewayTest::a magic link to an unknown address creates no account`
    — `./gw :shared:jvmTest --tests 'SupabaseAuthGatewayTest'`
  - Acceptance: AC-05, AC-08, AC-09
  - Notes: The class, the file, the role and the confinement allowlist entry all stay. The
    body changes: KMPAuth's `SupabaseAuthBackend` for sign-up, sign-in, sign-out and the
    live session; the SDK directly for the magic link (`createUser = false`), the magic-link
    exchange (`verifyEmailOtp`), the password change (`updateUser`) and the refresh. **The
    refresh must not be wrapped in the shared `call()` helper** — that helper turns a null
    result into an error, and here the null *is* the answer; a comment at the call site says
    so. The backend is built per call from the resolved client, so exactly one client and one
    session exist. The port's `currentSession()` triggers the SDK's session load, which is
    what runs the migration inside the restore's bound.

- [x] T007 Move the restore path onto the port's live session, and let the repository act on
      the refresh's two answers.
  - Files: `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/AuthRepository.kt`
  - Depends on: T003, T006
  - Verification: `AuthRepositoryTest::a rejected refresh token signs the user out`
    — `./gw :shared:jvmTest --tests 'AuthRepositoryTest'`
  - Acceptance: AC-05, AC-06, AC-11, AC-12, AC-13
  - Notes: The restore reads the live session through the port instead of four separate
    store reads, and the refresh's rejection is handled as a rejection — the sign-out branch
    that has been unreachable in production becomes reachable. The bounded read, the single
    failure report, the pending flag, the local-session fallback and the
    "a transport failure keeps the stored session" branch are all unchanged. The store
    dependency is dropped from the repository's constructor.

- [x] T010 Extend the vendor-confinement gate to the second vendor, and to the new seam.
  - Files: `shared/src/jvmTest/kotlin/com/singularity/todo/arch/VendorSdkConfinementTest.kt`
  - Depends on: T004, T006
  - Verification: `VendorSdkConfinementTest::the rule detects a vendor import outside a seam`
    — `./gw :shared:jvmTest --tests 'VendorSdkConfinementTest'`
  - Acceptance: AC-14
  - Notes: The scanned prefix must cover **both** `io.github.jan.supabase` and
    `com.mmk.kmpauth` — a rule that names one of them is a rule that measures nothing for
    the other, which is the failure this test's own comment warns about. The allowlist gains
    the new seam. The positive control must fire for **each** prefix, or it is only proving
    that one of them is still caught.

- [x] T013 Write the fake for the SDK's auth plugin, and the seam's tests.
  - Files: `shared/src/jvmTest/kotlin/com/singularity/todo/core/auth/FakeSupabaseAuth.kt`,
    `shared/src/jvmTest/kotlin/com/singularity/todo/core/auth/SupabaseAuthGatewayTest.kt`
  - Depends on: T006
  - Verification: `SupabaseAuthGatewayTest::a rejected refresh token is reported as a rejection, not as a transport failure`
    — `./gw :shared:jvmTest --tests 'SupabaseAuthGatewayTest'`
  - Acceptance: AC-05, AC-08, AC-09
  - Notes: The fake implements the SDK's 42-member `Auth` interface — three members
    meaningful, the rest stubbed. It captures the configure lambda the seam passes to
    `signInWith`, applies it to a real `OTP.Config`, and reads `createUser` off it, so
    `createUser = false` is **observed** rather than assumed. Carries the scenarios V-18,
    V-19 and V-20.

---

## Phase 4 — wiring and cleanup

- [x] T008 Rebind the auth layer in the dependency graph.
  - Files: `shared/src/commonMain/kotlin/com/singularity/todo/core/di/CoreDiModule.kt`
  - Depends on: T005, T006, T007
  - Verification: `AuthRepositoryTest::the local session contacts nothing`
    — `./gw :shared:jvmTest --tests 'AuthRepositoryTest'`
  - Acceptance: AC-16
  - Notes: Replace the `SecureSessionStore` binding with the `SecureSessionManager`, and
    wire the manager into the provider so the plugin is built with it. The `AuthGateway`,
    `AuthRepository` and `CurrentUser` bindings stay as they are. List composition only —
    no `includes()`, no `*domainModule().toTypedArray()`, no `factory { }` for a ViewModel —
    and the compiler plugin must still verify the graph.

- [x] T014 Update the repository's tests for the port's live session and the refresh's two
      answers.
  - Files: `shared/src/jvmTest/kotlin/com/singularity/todo/core/auth/AuthRepositoryTest.kt`
  - Depends on: T007
  - Verification: `AuthRepositoryTest::a store failure during a sign-in ends the session at the provider`
    — `./gw :shared:jvmTest --tests 'AuthRepositoryTest'`
  - Acceptance: AC-05, AC-06, AC-07, AC-11, AC-12
  - Notes: The `ScriptedGateway` already counts `signOutCalls`, which is what AC-07's
    assertion needs — the provider session that the device could not store must be ended.
    Carries the scenarios V-09 … V-17 and V-30. The repository's fake store is replaced by
    the new one.

---

## Phase 5 — cleanup and the device-facing pieces

- [x] T009 Delete the old credential store and retarget everything that wrote a session
      through it.
  - Files: `shared/src/commonMain/kotlin/com/singularity/todo/core/auth/SecureSessionStore.kt`
    (deleted),
    `shared/src/androidMain/kotlin/com/singularity/todo/test/stubs/TestAuthStubs.kt`,
    `androidApp/src/debug/kotlin/com/singularity/todo/debug/DebugSeedActivity.kt`
  - Depends on: T008
  - Verification: `SignOutKeepsLocalDataTest::signing out keeps the local data`
    — `./gw :shared:jvmTest --tests 'SignOutKeepsLocalDataTest'`
  - Acceptance: AC-01
  - Notes: The debug stub writes the session the Maestro flow corrupts, so it must write
    through the **new** store. If it is not retargeted, the flow
    `Maestro/flows/auth/12-unreadable-store-recovers.yaml` goes red rather than quietly
    green — it waits for the shell, and a stub that stores nothing never brings it up. That
    is the property that keeps the flow a guard instead of a decoration.

- [x] T015 Re-point the auth feature's tests at the new store, and keep the awaiting-
      confirmation outcome covered.
  - Files: `shared/src/jvmTest/kotlin/com/singularity/todo/feature/auth/SignOutKeepsLocalDataTest.kt`,
    `shared/src/jvmTest/kotlin/com/singularity/todo/feature/auth/AuthViewModelTest.kt`,
    `shared/src/jvmTest/kotlin/com/singularity/todo/feature/auth/AuthRestoreFailureUiTest.kt`
  - Depends on: T009
  - Verification: `AuthViewModelTest::a sign up awaiting email confirmation is not reported as a failure`
    — `./gw :shared:jvmTest --tests 'AuthViewModelTest'`
  - Acceptance: AC-10, AC-01
  - Notes: `SignOutKeepsLocalDataTest` constructs the old store directly and must be
    retargeted. The awaiting-confirmation outcome (`REQ-UA-014`) is exercised by the
    existing view-model test and must stay green — the gateway's sign-up now returns a user
    rather than a session, so the distinction between "awaiting confirmation" and "failed"
    is re-derived from the session state.

- [x] T016 Correct the bounded-read inventory for the store that replaced the one it names.
  - Files: `docs/architecture/bounded-read-inventory.md`
  - Depends on: T009
  - Verification: `AuthRepositoryTest::a failed restore is reported once and ends signed out`
    — `./gw :shared:jvmTest --tests 'AuthRepositoryTest'`
  - Acceptance: AC-16
  - Notes: The inventory's row names `core/auth/SecureSessionStore.kt:124-126` as bounded-read
    sites. The new store's reads are bounded by the same restore, so the row is repointed at
    the new file.

---

## Phase 6 — the gates

- [x] T017 Run the whole suite and the change's gates, and record the real outcome.
  - Files: none
  - Depends on: T001, T002, T003, T004, T005, T006, T007, T008, T009, T010, T011, T012,
    T013, T014, T015, T016
  - Verification: `AuthRepositoryTest::a rejected refresh token signs the user out`
    — `just jvm && just lint-changed && just os-validate && just gm auth`
  - Acceptance: AC-16
  - Notes: The gate run is one background task writing to a unique log under
    `build/diagnostics/`, not a sequence of polls. `just gm auth` needs an emulator; if no
    device is available it is recorded as `NOT_RUN`, never as passed. The result belongs in
    `results.md` as exactly one of `PASS`, `FAIL`, `NOT_RUN`, `BLOCKED` or `NOT_APPLICABLE` —
    a check that was never run is never described as passing.

---

## Coverage

Every acceptance criterion in `spec.md` is served by at least one task:

| Criterion | Tasks |
|---|---|
| AC-01 | T004, T009, T012, T015 |
| AC-02 | T004, T005, T012 |
| AC-03 | T004, T012 |
| AC-04 | T004, T012 |
| AC-05 | T006, T007, T013, T014 |
| AC-06 | T007, T014 |
| AC-07 | T014 |
| AC-08 | T006, T013 |
| AC-09 | T006, T013 |
| AC-10 | T015 |
| AC-11 | T007, T014 |
| AC-12 | T003, T005, T007, T014 |
| AC-13 | T007 |
| AC-14 | T010 |
| AC-15 | T011 |
| AC-16 | T001, T002, T008, T016, T017 |

**Three transitions have no `AC-NN`** — the sign-out, the ownership transfer and the
write-racing-a-delete — and are carried by the existing regression tests (T014, T015) and by
the store's own suite (T012). `spec.md` may not be edited by the plan step, so whether to
add a criterion for the sign-out's effect on the store is a decision for the `review-spec`
gate; it is recorded in `verification.md`'s `## Known limitations`.

**Three criteria depend on the seam becoming testable** — AC-08, AC-09 and the production
half of AC-05. `design.md` § "The seam's testability" states how, and § A-6 and A-7 state the
alternatives and what each costs.

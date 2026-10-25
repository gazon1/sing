# Verification — Replace the home-grown auth adapter with KMPAuth's Supabase backend

> Written by `speckit-engineering-verify-design`, **before any code exists**. Its purpose is to
> make it impossible to retro-fit evidence: the strategy is fixed now, and `verify` measures what
> actually happened against it.
>
> Nothing in this document may claim a test has run. Nothing has been implemented yet.

## Verification objectives

"Correct" for this change means three things, in order of how much they matter.

First, **nothing observable changes.** A user signs in, signs out, works offline, confirms a
magic link and reads their mail — the same outcomes, the same words on the screen, the same
sessions across a restart. The contract that says so is `user-authentication`
(`REQ-UA-001` … `REQ-UA-022`), and it is unchanged by this change except for two additions.

Second, **the one behaviour that was always required and was not reachable becomes
reachable.** A session the provider can no longer refresh must end the sign-in. Today the
production gateway cannot express that answer, so the requirement is satisfied only by a test
double (`model.md`, counterexample C-5).

Third, **the store survives the change that replaces it.** The credential store's
representation changes from four values to one. A session already on the device must be moved,
not discarded — otherwise every signed-in user is signed out by the update.

What this document fixes now is *how* each of those will be observed, and — equally
important — **which of them cannot be observed in the fast suite at all**, so the reviewer
decides about that before any code exists.

### The one structural problem this document has to solve

`SupabaseAuthGateway` — the vendor seam — **has no test of any kind today.** There is no
`SupabaseAuthGatewayTest` in the repository, and the seam's provider-facing behaviour
(the magic link's `createUser = false`, the OTP exchange, and the refresh's null-versus-error
mapping) is therefore unverified by every test in the suite.

That matters here because three acceptance criteria (AC-05's production half, AC-08, AC-09)
are *about the seam's provider calls*, and `SupabaseClient` is not fakeable: the seam holds
one, and the SDK's `Auth` plugin is reached through it. The repository's behaviour is tested
against a fake at the `AuthGateway` port (`ScriptedGateway` in `AuthRepositoryTest.kt`), and
that fake cannot see what the seam asks the provider.

There are three ways to close this, and the choice belongs to `design.md`:

| Option | What it costs | What it buys | Recommendation |
|---|---|---|---|
| **A — `design.md` introduces one narrow provider port inside the seam** | One project-owned interface with the three provider calls, implemented by the SDK and faked in tests | AC-05's production half, AC-08 and AC-09 become fast unit tests; the one untested file in the auth feature stops being untested | **Preferred.** The defect is named and real: *the vendor seam has no test* |
| **B — the local Supabase stack** (`docs/architecture/local-stack.md`) | Docker, a separate infrastructure repository, a second compose file | Real provider behaviour, end to end | Fallback. The stack is not in CI — CI runs `fast` — so the criteria stay unproven on every pull request |
| **C — accept them as unverified** | Nothing | Nothing | Not acceptable without the limitation being recorded below |

**Scenarios V-18, V-19 and V-20 are written for option A.** Under option B they become
integration scenarios against the local stack, tagged `slow`, and their coverage claim is
weakened accordingly — see `## Known limitations`.

## Acceptance criteria coverage

| Acceptance criterion | Scenarios | Status |
|---|---|---|
| AC-01 | V-01, V-02, V-07, V-14 | planned |
| AC-02 | V-04, V-05, V-07 | planned |
| AC-03 | V-06 | planned |
| AC-04 | V-03, V-11 | planned |
| AC-05 | V-09 (repository half), V-20 (production half) | planned — **the production half needs option A or B** |
| AC-06 | V-10, V-13 | planned |
| AC-07 | V-11 | planned |
| AC-08 | V-18 | planned — **needs option A or B** |
| AC-09 | V-19 | planned — **needs option A or B** |
| AC-10 | V-21 | planned |
| AC-11 | V-15 | planned |
| AC-12 | V-12 | planned |
| AC-13 | V-22 | planned |
| AC-14 | V-23 | planned |
| AC-15 | V-24 | planned |
| AC-16 | V-25 | planned |

Every `AC-NN` has at least one scenario. The three that depend on a design decision are
marked, not hidden.

## Scenarios

### V-01 — a sign-in persists the session in the secure store

- **Acceptance:** AC-01
- **Transition:** `Empty` --`storeSave(session)`--> `Present(session)` (model.md)
- **Preconditions:** an empty `MapSecureStorage`; an empty `newPreferencesStore()`; a store
  built over both; a session with an owner id, an email and two tokens.
- **Sequence:** save the session; then read the store's contents.
- **Expected state:** the store holds exactly one value, and it round-trips the owner id, the
  email and both tokens.
- **Expected side effects:** the plain-text preferences hold no token — the access token, the
  refresh token and the email are all absent from the preferences store.
- **Invariants checked:** INV-01, INV-02
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'SecureSessionManagerTest'`

### V-02 — a write replaces an existing session atomically

- **Acceptance:** AC-01
- **Transition:** `Present(session)` --`storeSave(session)`--> `Present(session)` (model.md)
- **Preconditions:** a store already holding a session, and a second, different session.
- **Sequence:** save the second session over the first; then read the store.
- **Expected state:** the store holds one value, and it is the second session's — no field of
  the first survives.
- **Expected side effects:** the number of stored values does not grow.
- **Invariants checked:** INV-02
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'SecureSessionManagerTest'`

### V-03 — a failed write leaves no session behind

- **Acceptance:** AC-04
- **Transition:** `Empty` --`storeSave(session)`--> `Present(session)`, failure branch (model.md)
- **Preconditions:** a `MapSecureStorage(failWrites = true)`; an empty store.
- **Sequence:** attempt to save a session.
- **Expected state:** the store still holds nothing — the read returns null — and the failure
  is reported as `AppError.Persistence` with the code `auth.session_not_stored`.
- **Expected side effects:** the plain-text preferences are untouched.
- **Invariants checked:** INV-02
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'SecureSessionManagerTest'`

### V-04 — a session in the previous store layout is migrated and remains usable

- **Acceptance:** AC-02
- **Transition:** `Empty` --`storeLoad()`--> `Present(session)` (model.md)
- **Preconditions:** a store seeded with the **previous** representation's four values — a
  refresh token, an owner id, an email and an access token — and an empty current
  representation.
- **Sequence:** load the session; then read the store's contents.
- **Expected state:** the session is usable: the owner id and both tokens are the ones that were
  stored before.
- **Expected side effects:** the current representation holds one value; **the previous
  representation's four values are gone** — they are erased only after the write succeeded.
- **Invariants checked:** INV-03, INV-04
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'SecureSessionManagerTest'`

### V-05 — a legacy record without an owner is moved as a token-only record

- **Acceptance:** AC-02
- **Transition:** `Empty` --`storeLoad()`--> `Present(tokenOnly)` (model.md)
- **Preconditions:** a store seeded with the previous representation's **three** values — a
  refresh token, an access token and an email, and no owner id. This is the state the old
  plaintext migration left behind (`core/auth/SecureSessionStore.kt:151-155`), and the
  previous representation holds no owner id at all.
- **Sequence:** load; then read the store's contents.
- **Expected state:** the tokens are in the current representation, and the record carries no
  owner id — so the restore finds no owner and ends signed out, which is what today's devices
  do with this state.
- **Expected side effects:** the previous representation's values are erased.
- **Invariants checked:** INV-03
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'SecureSessionManagerTest'`

### V-06 — a plain-text token is moved into the secure store and erased

- **Acceptance:** AC-03
- **Transition:** `Empty` --`storeLoad()`--> `Present(tokenOnly)` (model.md)
- **Preconditions:** a `newPreferencesStore()` seeded with a plain-text access token, a refresh
  token and an email; an empty secure store.
- **Sequence:** load; then read both stores.
- **Expected state:** the tokens are in the secure store.
- **Expected side effects:** **the plain-text copies are gone** — the access token, the refresh
  token and the email are all absent from the preferences store.
- **Invariants checked:** INV-01, INV-03
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'SecureSessionManagerTest'`

### V-07 — a record already in the current layout is loaded as-is and never displaced

- **Acceptance:** AC-01, AC-02
- **Transition:** `Present(session)` --`storeLoad()`--> `Present(session)` (model.md)
- **Preconditions:** the current representation holding a session; the previous representation
  also holding an **older** session; the plain-text preferences holding a third, older token.
- **Sequence:** load; then read the store's contents.
- **Expected state:** the session served is the one in the current representation — not the
  older one from the previous representation, and not the one from plain text.
- **Expected side effects:** the older copies are erased, and the current representation is
  left exactly as it was.
- **Invariants checked:** INV-04
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'SecureSessionManagerTest'`

### V-08 — the store is cleared on sign-out

- **Acceptance:** REQ-UA-021 (no `AC-NN` — see `## Known limitations`)
- **Transition:** `Present(session)` --`storeDelete()`--> `Empty` (model.md)
- **Preconditions:** the current representation holding a session.
- **Sequence:** delete the session; then read the store.
- **Expected state:** the store holds nothing.
- **Expected side effects:** the plain-text preferences are untouched — the device id and the
  local owner id are not secrets and do not live here.
- **Invariants checked:** INV-08
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'SecureSessionManagerTest'`

### V-09 — a rejected refresh token signs the user out

- **Acceptance:** AC-05
- **Transition:** `SignedIn` --`refreshOutcome(rejected)`--> `SignedOut` (model.md)
- **Preconditions:** a repository over a `ScriptedGateway` whose `refreshResult` is
  `Result.success(null)` — the rejection answer; a store holding a session.
- **Sequence:** run the restore.
- **Expected state:** the published session is `Session.SignedOut`, and the store holds
  nothing.
- **Expected side effects:** the queued outbox is untouched.
- **Invariants checked:** INV-06
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'AuthRepositoryTest'`

> The repository half of AC-05 is already covered by the existing test at
> `shared/src/jvmTest/kotlin/com/singularity/todo/core/auth/AuthRepositoryTest.kt:331`.
> **V-20 is the half that is not**: the production gateway producing that answer.

### V-10 — a transport failure keeps the stored session

- **Acceptance:** AC-06
- **Transition:** `SignedIn` --`refreshOutcome(transportFailure)`--> `SignedIn` (model.md)
- **Preconditions:** a repository over a `ScriptedGateway` whose `refreshResult` is
  `Result.failure(AppError.Network(...))`; a store holding a session.
- **Sequence:** run the restore.
- **Expected state:** the published session is `Session.SignedIn`, carrying the **stored**
  tokens — not a new session, and not a signed-out one.
- **Expected side effects:** the store still holds the session it held.
- **Invariants checked:** INV-06
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'AuthRepositoryTest'`

### V-11 — a store failure during a sign-in ends the provider session

- **Acceptance:** AC-04, AC-07
- **Transition:** `SignedOut` --`signIn()`--> `SignedIn`, failure branch (model.md)
- **Preconditions:** a repository over a `ScriptedGateway` whose `signInResult` is a success;
  a store over a `MapSecureStorage(failWrites = true)`.
- **Sequence:** sign in.
- **Expected state:** the published session is `Session.SignedOut`, and the attempt is a
  failure.
- **Expected side effects:** **`gateway.signOutCalls` is 1** — the provider session the device
  could not store is ended; the error's code is `auth.session_not_stored`; the message names
  the local failure and does not suggest the address or the password was wrong.
- **Invariants checked:** INV-09, INV-02
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'AuthRepositoryTest'`

### V-12 — a failed restore is reported once and ends signed out

- **Acceptance:** AC-12
- **Transition:** `Loading` --`restore()`--> `SignedOut` (model.md)
- **Preconditions:** a repository over a store that throws on every read
  (`ThrowingSecureStorage`), and a `ScriptedGateway`.
- **Sequence:** run the restore; then collect `restoreFailures`.
- **Expected state:** the published session is `Session.SignedOut` — never `Session.Loading`.
- **Expected side effects:** exactly one failure report; the failure channel completes.
- **Invariants checked:** INV-01
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'AuthRepositoryTest'`

> The variant where the store **never answers** is the same scenario with
> `HangingSecureStorage`, and it exercises the bound rather than the catch. Both fakes
> already exist (`shared/src/jvmTest/kotlin/com/singularity/todo/core/auth/AuthTestDoubles.kt:70`
> and `:92`).

### V-13 — the restore publishes the signed-in session

- **Acceptance:** AC-06
- **Transition:** `Loading` --`restore()`--> `SignedIn` (model.md)
- **Preconditions:** a repository over a store holding a complete session and a
  `ScriptedGateway` whose `refreshResult` is a refreshed session.
- **Sequence:** run the restore.
- **Expected state:** the published session is `Session.SignedIn` carrying the **refreshed**
  tokens, and its owner id is the stored one.
- **Expected side effects:** the store holds the refreshed session.
- **Invariants checked:** INV-02, INV-05
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'AuthRepositoryTest'`

### V-14 — a successful sign-in publishes the session

- **Acceptance:** AC-01
- **Transition:** `SignedOut` --`signIn()`--> `SignedIn` (model.md)
- **Preconditions:** a repository over a store and a `ScriptedGateway` whose `signInResult` is
  a session.
- **Sequence:** sign in.
- **Expected state:** the published session is `Session.SignedIn` with the session's owner id,
  email and tokens.
- **Expected side effects:** the store holds the session; the pending indicator is cleared.
- **Invariants checked:** INV-01, INV-02
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'AuthRepositoryTest'`

### V-15 — the local session contacts nothing

- **Acceptance:** AC-11
- **Transition:** `SignedOut` --`startLocalSession()`--> `Anonymous`, and `Loading`
  --`restore()`--> `Anonymous` (model.md)
- **Preconditions:** a repository over a `ScriptedGateway` that **counts every call**; a
  preferences store with no session.
- **Sequence:** start the local session; then restore.
- **Expected state:** the published session is `Session.Anonymous` with the local owner id.
- **Expected side effects:** **the gateway's call counters are all zero** — no sign-in, no
  sign-up, no refresh, no magic link. No provider-side identity is created.
- **Invariants checked:** INV-07
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'AuthRepositoryTest'`

### V-16 — signing out clears the session and keeps the local data

- **Acceptance:** REQ-UA-021 (no `AC-NN` — see `## Known limitations`)
- **Transition:** `SignedIn` --`signOut()`--> `SignedOut` (model.md)
- **Preconditions:** a repository over a store holding a session and a local owner id.
- **Sequence:** sign out.
- **Expected state:** the published session is `Session.SignedOut`.
- **Expected side effects:** the store holds nothing; the local owner id is still there; the
  queued outbox is untouched.
- **Invariants checked:** INV-08
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'SignOutKeepsLocalDataTest'`

### V-17 — a failed ownership transfer returns to the offline session

- **Acceptance:** REQ-UA-022 (no `AC-NN` — see `## Known limitations`)
- **Transition:** `Anonymous` --`signIn()`--> `SignedIn`, failure branch (model.md)
- **Preconditions:** an anonymous session holding local rows; a transfer that fails.
- **Sequence:** sign in and attempt the claim.
- **Expected state:** the published session is `Session.Anonymous` again — the device is back
  on the session it started from.
- **Expected side effects:** the failure is reported; the local rows are intact.
- **Invariants checked:** INV-08
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'LocalAccountClaimerTest'`

### V-18 — a magic link to an unknown address creates no account

- **Acceptance:** AC-08
- **Transition:** `SignedOut` --`sendMagicLink()`--> `SignedOut` (model.md)
- **Preconditions:** a provider that records the request it was sent — the port option A
  introduces — and an address the provider does not know.
- **Sequence:** send the magic link.
- **Expected state:** the session is unchanged.
- **Expected side effects:** the request the provider received asks for a link to an **existing**
  account — it does not ask for one to be created. Under the provider, no account exists
  afterwards.
- **Invariants checked:** INV-01
- **Test level:** unit (option A) | integration against the local Supabase stack (option B)
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'SupabaseAuthGatewayTest'`

### V-19 — a magic link token is exchanged for a session

- **Acceptance:** AC-09
- **Transition:** `SignedOut` --`verifyMagicLink(email, token)`--> `SignedIn` (model.md)
- **Preconditions:** a provider that returns a session for a valid token.
- **Sequence:** exchange the token.
- **Expected state:** the returned session carries an owner id, an email and both tokens.
- **Expected side effects:** none beyond the provider's own session creation.
- **Invariants checked:** INV-05
- **Test level:** unit (option A) | integration against the local Supabase stack (option B)
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'SupabaseAuthGatewayTest'`

### V-20 — a rejected refresh token is reported as a rejection, not as a transport failure

- **Acceptance:** AC-05 (the production half)
- **Transition:** `SignedIn` --`refreshOutcome(rejected)`--> `SignedOut`, at the seam (model.md)
- **Preconditions:** a provider that answers the refresh with a rejection.
- **Sequence:** refresh the stored session through the seam.
- **Expected state:** the seam's answer is the **rejection** — the distinct answer, not the
  transport failure — and the repository's sign-out branch is reached.
- **Expected side effects:** the store is cleared.
- **Invariants checked:** INV-06
- **Test level:** unit (option A) | integration against the local Supabase stack (option B)
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'SupabaseAuthGatewayTest'`

### V-21 — a sign-up awaiting email confirmation is not reported as a failure

- **Acceptance:** AC-10
- **Transition:** `SignedOut` --`signUp()`--> `SignedOut` (model.md)
- **Preconditions:** a `ScriptedGateway` whose `signUpResult` is a session with no email, and
  a view model over it.
- **Sequence:** sign up.
- **Expected state:** the outcome is reported as awaiting confirmation — not as a failure, and
  not as a completed sign-in; the published session is `Session.SignedOut`.
- **Expected side effects:** the pending indicator is cleared; the screen says to check the
  mail.
- **Invariants checked:** INV-01
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'AuthViewModelTest'`

### V-22 — the signed-in identity reaches every consumer unchanged

- **Acceptance:** AC-13
- **Transition:** `SignedIn` --`providerSessionChanged(session)`--> `SignedIn` (model.md)
- **Preconditions:** a repository publishing a session; a consumer reading the current user.
- **Sequence:** publish the session, then read the identity from the consumer.
- **Expected state:** the consumer sees the session's owner id — not a placeholder, and not a
  different id.
- **Expected side effects:** none.
- **Invariants checked:** INV-05
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'ReadToolsProfileAwareTest'`

> The existing test is the one that caught the collector race documented at
> `core/auth/CurrentUser.kt:35-45`; it is the cheapest guard that the identity still reaches
> its consumers after the session's publication changes.

### V-23 — only the seams import a vendor SDK, and the rule can still fail

- **Acceptance:** AC-14
- **Transition:** not a transition — a property of every production file
- **Preconditions:** the repository's production sources under `commonMain`.
- **Sequence:** scan every production file for both vendor prefixes; then feed the rule a
  sample that does import one.
- **Expected state:** no file outside the declared seams imports `io.github.jan.supabase` **or**
  `com.mmk.kmpauth`; every declared seam still exists and still imports its vendor; and the
  positive control **still fires** for each prefix.
- **Expected side effects:** none.
- **Invariants checked:** INV-05
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'VendorSdkConfinementTest'`

### V-24 — the secure store is excluded from backup and device transfer

- **Acceptance:** AC-15
- **Transition:** not a transition — a property of the store's file
- **Preconditions:** the Android backup rule files.
- **Sequence:** read `backup_rules.xml` and `data_extraction_rules.xml`.
- **Expected state:** the secure store's file is excluded from cloud backup **and** from
  device transfer, in both rule files.
- **Expected side effects:** none.
- **Invariants checked:** INV-01
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'BackupRulesTest'`

### V-25 — the whole suite and the gates pass

- **Acceptance:** AC-16
- **Transition:** not a transition — the aggregate
- **Preconditions:** a clean checkout with this change applied.
- **Sequence:** run the JVM suite, the changed-file lint, the OpenSpec validation and the
  gate suite.
- **Expected state:** every check reports success; the OpenSpec change directory validates
  against its schema.
- **Expected side effects:** none.
- **Invariants checked:** all
- **Test level:** integration
- **Platform:** jvm
- **Deterministic reproduction:** `just jvm && just lint-changed && just os-validate`

## Unit tests

- Virtual time via `runTest` + `advanceUntilIdle()`. **Never** `delay(N)` as synchronization.
- `MutableClock.kt`, `AwaitState.kt` from `shared/src/commonTest/.../test/helpers/`.
- Fakes from `shared/src/commonTest/.../test/fakes/FakeRepositories.kt`. MockK only for outgoing
  calls you assert on, never for state.
- Every new test class carries `@Tag("fast")` or `@Tag("slow")`. `slow` means it crosses a process
  boundary — Compose harness, real file/DB/clock, Konsist scan, subprocess — not "slow to run".
- No `@Disabled` and no assumption guards. They make `check-test-runs.py` fail.

**The fakes this design needs already exist** and are reused rather than rewritten:

| Fake | Where | What it expresses |
|---|---|---|
| `MapSecureStorage(failWrites, failWriteNumber)` | `AuthTestDoubles.kt:30` | A keychain that refuses every write, or refuses the *n*-th write — the partial-write case |
| `HangingSecureStorage` | `AuthTestDoubles.kt:70` | A store that never answers — the bounded-restore case |
| `ThrowingSecureStorage` | `AuthTestDoubles.kt:92` | A store that raises on every call — the uninstall/reinstall case |
| `newPreferencesStore()` / `hangingPreferencesStore()` / `throwingPreferencesStore()` | `AuthTestDoubles.kt:101, 122, 137` | The plain-text store, in each of its three failure shapes |
| `ScriptedGateway` | `AuthRepositoryTest.kt:873` | A gateway whose every answer is set by the test, **and which counts every call** — `signOutCalls` is what V-11 asserts on |

`MapSecureStorage.writeAttempts` is the assertion V-03 uses to prove the failed write was
*attempted* and not silently skipped.

**New test classes required:** `SecureSessionManagerTest` (replacing `SecureSessionStoreTest`),
`SupabaseAuthGatewayTest` (does not exist today), `BackupRulesTest` (does not exist today).

## Integration tests

- **`SupabaseAuthGatewayTest`** against the local Supabase stack
  (`docs/architecture/local-stack.md`), **only under option B**. The stack is a separate
  infrastructure repository with its own compose file and its own `.env`; it is not part of CI.
  Tagged `slow`.
- **The Android encrypted store** is not exercised in the JVM suite: `EncryptedSharedPreferences`
  needs the Keystore. The store's *behaviour* is exercised through `SecureStorage`, and the
  platform binding is covered by the Maestro flow in `## Regression tests`.
- **Room, DataStore and WorkManager** are not touched by this change; no integration test is
  added for them.

## UI / E2E tests

*(when applicable — no screen changes, so no new flow is required; the existing flows are the
regression signal)*

This change alters no screen, no copy and no navigation. The twelve flows under
`Maestro/flows/auth/` are unaffected in what they assert, and one of them is directly relevant:

- **`Maestro/flows/auth/12-unreadable-store-recovers.yaml`** — installs a session, corrupts the
  encrypted store file, relaunches and asserts the login screen appears within the restore
  budget. It is the only end-to-end proof that the store's layout is really on the device, and
  it covers the unreadable-store case (`REQ-UA-015`).
  - **Its dependency on this change is exact.** The session it installs is written by
    `Maestro/helpers/session-signedin.yaml` → the debug link `todo-debug://seed?session=signedin`
    → `androidApp/src/debug/.../DebugSeedActivity.kt:385` → `TestAuthStubs.installSignedInSession`
    → the store's write. If the stub is not retargeted to the new representation, the helper's
    wait for `nav_tab_today` fails and the flow goes red — **it does not go quietly green**.
    That is the property that makes this flow a usable guard rather than a decoration: a store
    that was never written cannot show the regression, and the flow would fail rather than
    pass for the wrong reason.
- **`Maestro/flows/auth/05-session-invalidation.yaml`** — asserts that signing out keeps the
  local data and that the auth guard returns to the login screen. It uses the **stubbed** auth
  repository in the test environment, so it exercises the sign-out *flow*, not the real
  provider path. It is a regression signal, not evidence for AC-05.
- **`Maestro/flows/auth/06-signup-unconfirmed.yaml`** — the device-level counterpart of V-21.

No new Maestro flow is required: the change introduces no new screen state, no new error
message and no new navigation.

## State-transition coverage

| Transition | Scenario | Acceptance |
|---|---|---|
| `Loading --restore()--> SignedIn` | V-13 | AC-06 |
| `Loading --restore()--> Anonymous` | V-15 | AC-11 |
| `Loading --restore()--> SignedOut` | V-12 | AC-12 |
| `SignedOut --signIn()--> SignedIn` (success) | V-14 | AC-01 |
| `SignedOut --signIn()--> SignedIn` (store failure) | V-11 | AC-04, AC-07 |
| `SignedOut --signUp()--> SignedOut` | V-21 | AC-10 |
| `SignedOut --sendMagicLink()--> SignedOut` | V-18 | AC-08 |
| `SignedOut --verifyMagicLink()--> SignedIn` | V-19 | AC-09 |
| `SignedOut --startLocalSession()--> Anonymous` | V-15 | AC-11 |
| `SignedIn --refreshOutcome(rejected)--> SignedOut` | V-09, V-20 | AC-05 |
| `SignedIn --refreshOutcome(transportFailure)--> SignedIn` | V-10 | AC-06 |
| `SignedIn --providerSessionChanged()--> SignedIn` | V-22 | AC-13 |
| `SignedIn --signOut()--> SignedOut` | V-16 | REQ-UA-021 |
| `Anonymous --signIn()--> SignedIn` (transfer failure) | V-17 | REQ-UA-022 |
| `Empty --storeLoad()--> Present(session)` (current layout) | V-07 | AC-01, AC-02 |
| `Empty --storeLoad()--> Present(session)` (legacy complete) | V-04 | AC-02 |
| `Empty --storeLoad()--> Present(tokenOnly)` (legacy partial) | V-05 | AC-02 |
| `Empty --storeLoad()--> Present(tokenOnly)` (plaintext) | V-06 | AC-03 |
| `Empty --storeSave()--> Present(session)` (success) | V-01 | AC-01 |
| `Empty --storeSave()--> Present(session)` (failure) | V-03 | AC-04 |
| `Present(session) --storeSave()--> Present(session)` | V-02 | AC-01 |
| `Present(tokenOnly) --storeLoad()--> Present(tokenOnly)` | V-07 | AC-02 |
| `Present(session) --storeDelete()--> Empty` | V-08 | REQ-UA-021 |
| `Present(tokenOnly) --storeDelete()--> Empty` | V-08 | REQ-UA-021 |

**Every transition in `model.md` is covered, including both branches of the three transitions
that have one.** The transitions the model names but does not table — the provider's client
rebuild, and `updatePassword` — change no state this model describes, and are covered by the
regression set rather than by a scenario.

**Scenario ids.** `model.md` used its own `V-NN` placeholders; this document assigns the
authoritative ids. The two sets differ only where this document splits one transition into its
success and failure branches (the model's V-04 became V-11 and V-14; its V-16 became V-01 and
V-03; its V-12 became V-04 and V-07). `model.md` states that this document owns the numbering.

## Concurrency and cancellation tests

The store's transitions are not obviously concurrent, and that is exactly why they need tests:
the model names four counterexamples, and three of them are about ordering rather than about a
value.

### V-26 — a write racing a delete does not resurrect the session

- **Acceptance:** REQ-UA-021 (no `AC-NN` — see `## Known limitations`)
- **Transition:** `Present(session)` --`storeDelete()`--> `Empty` racing `Present(session)`
  --`storeSave(session)`--> `Present(session)` (model.md, counterexample C-2)
- **Preconditions:** a store whose writes can be interleaved deliberately — a `SecureStorage`
  whose `write` suspends on a signal the test controls.
- **Sequence:** start the save and hold it; run the delete; release the save.
- **Expected state:** the store holds nothing. A record that reappears after a sign-out is a
  session the user asked to end and cannot.
- **Expected side effects:** none.
- **Invariants checked:** INV-08
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'SecureSessionManagerTest'`

### V-27 — the migration runs once, not on every read

- **Acceptance:** AC-02
- **Transition:** `Empty` --`storeLoad()`--> `Present(session)` (model.md, counterexample C-1)
- **Preconditions:** a store holding the previous representation's session, and a writer that
  counts writes.
- **Sequence:** load twice; then plant a **different** value in the plain-text preferences
  between the two loads.
- **Expected state:** the second load serves the same session as the first.
- **Expected side effects:** the write count did not grow on the second load — the migration is
  at most once per process; and the planted value is still in the preferences, unmoved.
- **Invariants checked:** INV-03, INV-04
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'SecureSessionManagerTest'`

### V-28 — two concurrent first reads migrate once

- **Acceptance:** AC-02
- **Transition:** `Empty` --`storeLoad()`--> `Present(session)` (model.md, counterexample C-1)
- **Preconditions:** a store holding the previous representation's session, and a reader that
  can be released together.
- **Sequence:** start two loads; release them together; then read the store.
- **Expected state:** the store holds one migrated session, and the previous representation is
  erased.
- **Expected side effects:** the migration was performed once — the write count is one, not
  two.
- **Invariants checked:** INV-03, INV-04
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'SecureSessionManagerTest'`

### V-29 — a cancellation between the migration's write and its erase leaves the session in one place

- **Acceptance:** AC-02, AC-03
- **Transition:** `Empty` --`storeLoad()`--> `Present(session)` (model.md, counterexample C-3)
- **Preconditions:** a store whose `delete` suspends on a signal the test controls, seeded with
  the previous representation's session.
- **Sequence:** load; let the write land; cancel the coroutine before the erase; then load
  again.
- **Expected state:** the second load serves the migrated session — the one in the current
  representation, not the leftover in the previous one.
- **Expected side effects:** the leftover in the previous representation is erased by the
  second load and never re-imported.
- **Invariants checked:** INV-03, INV-04
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'SecureSessionManagerTest'`

### V-30 — a cancelled restore leaves the app signed out, not loading

- **Acceptance:** AC-12
- **Transition:** `Loading` --`restore()`--> `SignedOut` (model.md)
- **Preconditions:** a repository over a `HangingSecureStorage`.
- **Sequence:** cancel the scope that owns the restore.
- **Expected state:** the published session is not `Session.Loading` — the application cannot
  be left behind a permanent spinner.
- **Expected side effects:** the failure report is emitted once; no coroutine is left suspended.
- **Invariants checked:** INV-01
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `./gw :shared:jvmTest --tests 'AuthRepositoryTest'`

`DebugProbes.dumpCoroutines()` can assert no coroutine was left suspended in V-30. It is **JVM
only** — it needs the ByteBuddy agent and does not work on Android — so the scenario records
that limitation below.

## Failure injection and recovery

One entry per failure transition in `model.md`: how the effect is made to fail, and what the
recovery expectation is.

| The failure | How it is injected | Scenario | Recovery expectation |
|---|---|---|---|
| The store write during a sign-in | `MapSecureStorage(failWrites = true)` | V-03, V-11 | The store holds nothing; the provider session is ended; the attempt reports the local cause |
| The store write during a **migration** | `MapSecureStorage(failWrites = true)` with the previous representation seeded | V-04 (failure variant) | The previous representation is **untouched** and is read again next launch; nothing is erased |
| The store write fails on the *n*-th write | `MapSecureStorage(failWriteNumber = 2)` | V-03 | The store holds no record — a blanket failure is undone by any all-or-nothing caller, so the *n*-th failure is the one that distinguishes them |
| The store read raises | `ThrowingSecureStorage` | V-12 | Signed out after the bound; one failure report |
| The store read never answers | `HangingSecureStorage` | V-12, V-30 | Signed out after the bound; one failure report; never a permanent spinner |
| The preferences read raises | `throwingPreferencesStore` | V-12 | Signed out; one failure report |
| The provider refresh is rejected | `ScriptedGateway.refreshResult = Result.success(null)` | V-09, V-20 | Signed out; the store is cleared; the outbox survives |
| The provider refresh fails in transport | `ScriptedGateway.refreshResult = Result.failure(...)` | V-10 | The stored session is kept |
| The provider sign-in fails | `ScriptedGateway.signInResult = Result.failure(...)` | V-14 (failure variant) | The session is unchanged; the failure is reported without revealing whether the address exists |
| The provider sign-out fails | `ScriptedGateway.signOutResult = Result.failure(...)` | V-16 | The local sign-out still happens |
| The local marker write fails | a preferences store that throws on write | V-15 (failure variant) | The session stays signed out; the failure is reported |
| The ownership transfer fails | the claimer's transfer double returns a failure | V-17 | The device returns to the offline session, with its data intact |

## Regression tests

Existing tests that must stay green, and the command that runs them.

| Test | What it constrains | Command |
|---|---|---|
| `AuthRepositoryTest` | The repository's four rules, the restore, the pending flag, the sign-up-without-a-session case | `./gw :shared:jvmTest --tests 'AuthRepositoryTest'` |
| `AuthDomainTest` | Address normalisation and the length bounds | `./gw :shared:jvmTest --tests 'AuthDomainTest'` |
| `AuthViewModelTest` | The auth screen's outcomes, including awaiting confirmation | `./gw :shared:jvmTest --tests 'AuthViewModelTest'` |
| `AuthRestoreFailureUiTest` | The restore-failure report reaching the screen | `./gw :shared:jvmTest --tests 'AuthRestoreFailureUiTest'` |
| `SignOutKeepsLocalDataTest` | Sign-out keeps the local owner id and the outbox | `./gw :shared:jvmTest --tests 'SignOutKeepsLocalDataTest'` |
| `LocalAccountClaimerTest` | The ownership transfer, and its failure path | `./gw :shared:jvmTest --tests 'LocalAccountClaimerTest'` |
| `AccountSwitcherTest` | Switching accounts | `./gw :shared:jvmTest --tests 'AccountSwitcherTest'` |
| `OwnerScopedEraserTest`, `OwnerRowIdResolverTest` | The owner scoping the session's id feeds | `./gw :shared:jvmTest --tests 'OwnerScopedEraserTest'` |
| `ReadToolsProfileAwareTest` | The identity reaching a consumer that reads it — the test that caught the collector race | `./gw :shared:jvmTest --tests 'ReadToolsProfileAwareTest'` |
| `PlaintextTokenIsolationTest` | No token is written to plain-text preferences — the gate that fails if one appears | `./gw :shared:jvmTest --tests 'PlaintextTokenIsolationTest'` |
| `VendorSdkConfinementTest` | No vendor type outside the seams, and the rule's own positive control | `./gw :shared:jvmTest --tests 'VendorSdkConfinementTest'` |
| `SupabaseClientProviderTest` | One client, cached by configuration | `./gw :shared:jvmTest --tests 'SupabaseClientProviderTest'` |
| `SecureSessionStoreTest` | **Replaced** by `SecureSessionManagerTest`; its scenarios are carried over, not dropped — V-04, V-05, V-06 and V-03 are its four subjects | — |
| `Maestro/flows/auth/12-unreadable-store-recovers.yaml` | The store's layout is really on the device, and an unreadable store recovers | `just gm auth` |
| `Maestro/flows/auth/05-session-invalidation.yaml` | Sign-out keeps the data; the guard returns to the login screen | `just gm auth` |
| `Maestro/flows/auth/06-signup-unconfirmed.yaml` | The awaiting-confirmation outcome, on the device | `just gm auth` |

## Observability and diagnostics

*(when applicable — this change is not a diagnostic change, but two of its failures are invisible
without one, and they are named here for that reason)*

The failure this change fixes — a rejected refresh token treated as a transport failure — is
silent. The app reports a signed-in user whose every request is answered with an
unauthorised error, and nothing in the logs says "the refresh token was rejected".

- **Data needed to detect the problem:** the refresh outcome's **kind** — rejected, transport
  failure, or refreshed — not its message.
- **Where it is observed:** one log line at the seam, at the point where the outcome is
  classified. It must distinguish the three outcomes by kind, not by text, because the
  provider's wording for a rejection is not stable.
- **Correlation:** the seam's log line is emitted inside the refresh call that produced it, so
  it needs no separate operation id — the call boundary is the correlation.
- **Redaction:** the access token, the refresh token and the session payload must never be
  logged. The email is already redacted elsewhere (`core/auth/AuthRepository.kt:521` uses
  `Redaction::redactEmail`), and the same rule applies here. The *kind* of the failure is the
  useful part; the values are not.
- **How we will know it helped:** V-20 asserts the seam distinguishes the rejection from the
  transport failure, and V-09 asserts the repository signs the user out on the rejection. If a
  future change collapses the two again, both scenarios fail — which is the check, and it does
  not depend on anyone reading a log.

The same discipline applies to the migration: the existing code logs a warning when a
plain-text copy is discarded because the secure store already holds a newer session
(`core/auth/SecureSessionStore.kt:140`). That warning is carried over, because "the leftover
was discarded" is a fact an operator may need and a user will never see.

## Commands and expected outcomes

```
./gw :shared:compileKotlinJvm                 → the module compiles
./gw :shared:jvmTest --tests 'SecureSessionManagerTest'   → the store's scenarios pass
./gw :shared:jvmTest --tests 'AuthRepositoryTest'         → the repository's scenarios pass
./gw :shared:jvmTest --tests 'SupabaseAuthGatewayTest'    → the seam's scenarios pass (option A)
./gw :shared:jvmTest --tests 'VendorSdkConfinementTest'   → the confinement rule holds, prefix by prefix
./gw :shared:jvmTest --tests 'BackupRulesTest'            → the store's file is excluded from backup and transfer
./gw :shared:jvmTest --tests 'PlaintextTokenIsolationTest' → no token is written to plain-text preferences
just jvm                                         → the whole JVM suite passes
just lint-changed                                → detekt over the changed files passes
just os-validate                                 → the OpenSpec change directory validates
just gm auth                                     → the auth Maestro flows pass on a device
```

`./gw` is the repository's own Gradle wrapper, and it is used throughout because it gives the
worktree a private `GRADLE_USER_HOME` (`AGENTS.md`). `spec.md`'s `Verification:` lines name
`./gradlew` instead; the two are equivalent, and this document's commands are the ones to
copy.

## Known limitations

1. **The three criteria that needed a seam are provable in the fast suite.** This
   limitation was written before the change decided between the three options, and it
   left the choice to the reviewer. Option A was taken: the SDK's auth plugin is
   replaced by a fake that speaks the real `HttpClientCall` over a stub engine, so
   `SupabaseAuthGatewayTest` proves AC-08, AC-09 and the production half of AC-05 —
   no local Supabase stack, and nothing CI cannot run.

2. **Three transitions have no `AC-NN`.** The sign-out (V-08, V-16) and the ownership transfer
   (V-17) are carried by existing regression tests rather than by acceptance criteria in
   `spec.md`. This step may not edit `spec.md`. **The reviewer must either accept the existing
   tests as the binding or add an acceptance criterion** covering the sign-out's effect on the
   store. The same applies to V-26, the write-races-a-delete scenario.

3. **The provider-session-change race is determined.** It was not, when this document was
   written: whether a session change arriving between `Loading` and the restore's result is
   reachable depends on the provider SDK's ordering, which this change did not fix and the
   code did not guard. The change `observe-the-provider-session` settles it — the
   application observes the provider's own status and republishes a replaced session under
   the guard the model names: only while it is signed in, and only for the same account. A
   provider that holds no session, or one that reports another account's session, changes
   nothing. What is left — a change arriving while the application is not signed in — is
   answered by the restore, which reads the provider's session itself and publishes that.

4. **`DebugProbes.dumpCoroutines()` is JVM-only.** V-30's assertion that no coroutine is left
   suspended needs the ByteBuddy agent and does not work on Android. The scenario's
   cancellation assertion therefore runs on the JVM only, and the Android behaviour is left to
   the Maestro flow.

5. **The Android encrypted store is not exercised in the JVM suite.** `EncryptedSharedPreferences`
   needs the Keystore, so the store's behaviour is verified through the `SecureStorage` port
   and the platform binding is covered by `Maestro/flows/auth/12-unreadable-store-recovers.yaml`
   — a device test, not a unit test, and not part of the PR-blocking suite.

6. **The local Supabase stack is not available in CI.** It needs Docker and a separate
   infrastructure repository (`gazon1/supabase-repo-sing`), so any scenario that depends on it
   is a `slow` test that a pull request does not run.

7. **The `auth-kt` version question is a dependency fact, not a behavioural one.** The
   provider backend is published against a different version of the SDK than the application
   pins, and whether the two bind at runtime is settled by resolving the dependency — not by a
   scenario. It is `investigation.md`'s R-1, and it is settled before the scenarios are run.

# Design — Replace the home-grown auth adapter with KMPAuth's Supabase backend

## Summary

The application's authentication is a hand-written adapter over one vendor SDK plus a
hand-written credential store. This change replaces both with KMPAuth's Supabase backend
and a session the SDK owns and persists through the app's existing secure-storage port.

Three things are decided here that the spec left open.

**The seam keeps three direct SDK calls.** KMPAuth's Supabase backend cannot express three
of the app's seven provider operations — the magic link that must not create an account, the
magic-link exchange, and the refresh that must distinguish "rejected" from "network failed".
Each divergence is a contract difference, not a missing feature, and the design keeps the
existing SDK calls for exactly those three.

**The seam becomes testable.** `SupabaseAuthGateway` has no test of any kind today, and three
acceptance criteria are about it. The SDK's `Auth` plugin is an interface, so the seam's
dependency on it is substitutable; the cost is one fake with 42 members, and the design states
that cost rather than hiding it.

**One refresh answer becomes reachable.** The production gateway cannot currently return the
"rejected" answer the repository reads as "sign out", so `REQ-UA-003`'s signed-out state is
unreachable in production. The rewrite removes the wrapper that was collapsing the two
outcomes, and the design says so loudly enough that nobody puts it back.

## Current architecture and affected modules

The auth layer is three modules stacked, and only the middle one is really hand-written.

| Module | File | What it is |
|---|---|---|
| The port | `core/auth/AuthGateway.kt:33` | Seven operations in project vocabulary, all `Result<T>` over `RemoteSession` (`:86-92`). One production implementation (`AuthRepository.kt:155`). |
| The vendor seam | `core/auth/SupabaseAuthGateway.kt:29` | Calls the SDK directly for all seven operations. Reads the session back rather than trusting any return value (`:19-27`). |
| The repository | `core/auth/AuthRepository.kt` | The session state machine: pending flag, bounded restore, local session, the outbox-untouched rule. |
| The credential store | `core/auth/SecureSessionStore.kt:42` | Four separately written keychain values, a partial write undone, a one-shot plaintext migration. |

`CurrentUser` (`core/auth/CurrentUser.kt:28`) is the fan-out point: ~82 production files read
the identity through it. `core/sync` reads `session.accessToken` in six places.

**The modules this change touches, and nothing else:** `core/auth` (six files), the auth
entries in `core/di/CoreDiModule.kt`, two debug/stub consumers of the old store, and the
vendor-confinement gate. **No** change to `core/sync`, `core/database`, `feature/*`, the Room
schema, the MCP server, or the network API.

## Proposed solution

```
              ┌───────────────── domain and feature layers (unchanged) ────────────────┐
              │  feature/auth/*          core/sync/*            feature/* (82 files)  │
              └───────────┬───────────────────┬────────────────────┬─────────────────┘
                          │ AuthRepository     │ CurrentUser        │ Session.SignedIn
                  ┌───────┴───────────────────┴────────────────────┴─────────────────┐
                  │  SupabaseAuthRepository — state machine, bounded restore,          │
                  │  local session, outbox untouched                                    │
                  └───────┬──────────────────────────────────────┬─────────────────────┘
                          │ AuthGateway (project port)            │
                  ┌───────┴──────────────────────────────────────┴─────────────────────┐
                  │  SupabaseAuthGateway — THE VENDOR SEAM                             │
                  │                                                                          │
                  │   KMPAuth:       SupabaseAuthBackend(client)                          │
                  │                    ├ signUp / signIn / signOut                       │
                  │                    └ currentUser / currentUserFlow                   │
                  │   direct SDK:    signInWith(OTP) { createUser = false }               │
                  │                  verifyEmailOtp(MAGIC_LINK, email, token)             │
                  │                  refreshSession(refreshToken)  ← null means rejected  │
                  │                  updateUser { password = … }                          │
                  └───────┬──────────────────────────────────────┬─────────────────────┘
                          │ Auth (SDK interface — substitutable)  │
                  ┌───────┴───────────────────────┐   ┌─────────┴──────────────────────┐
                  │  SupabaseClientProvider      │   │  SecureSessionManager (NEW)     │
                  │  builds the one client;      │   │  io.github.jan.supabase.auth     │
                  │  installs the SessionManager │   │    .SessionManager              │
                  └───────────────────────────────┘   │  ↕ SecureStorage (keychain)     │
                                                      │  ↕ DataStoreSessionStore         │
                                                      │    (legacy source, read-only)    │
                                                      └─────────────────────────────────┘
```

### The store — one value, one atomic write

`SecureSessionManager` (new, `core/auth/SecureSessionManager.kt`) implements the SDK's
`SessionManager` over the app's `SecureStorage` port, and owns the migration.

- **One key, one write.** The current store writes four keys one at a time and undoes a
  partial write (`SecureSessionStore.kt:72-105`). One serialized value is atomic by
  construction, which satisfies `REQ-UA-012` more strongly than write-then-undo.
- **A project-level record, not a serialized vendor type.** The persisted value is a
  project-owned `@Serializable` record — access token, refresh token, owner id, email,
  expiry — and the seam converts it to and from the SDK's `UserSession`. No vendor type
  reaches the keychain payload, and the payload stays auditable. The owner id is nullable,
  because the legacy migration has no owner to store (see below).
- **The migration has two legacy sources, and the keychain wins.** The previous layout is
  four keychain values; the pre-keychain layout is plain-text preferences. The keychain is
  newer, so it is read first, and plain text is only consulted when the keychain has
  nothing. Each source is written to the new key **first** and erased **second**, and a
  failed write erases nothing (`SecureSessionStore.kt:21-33`).
- **A record without an owner is kept, and is not a session.** The old plaintext migration
  wrote access token, refresh token and email but no owner id (`SecureSessionStore.kt:151-155`),
  because the plain preferences never held one (`DataStoreSessionStore.kt:47-58`). Such a
  record is moved as-is — it leaves plain text, as `REQ-UA-013` requires — and the restore
  finds no owner and ends signed out, which is exactly what today's devices do with this
  state. Dropping it instead would contradict `REQ-UA-013`'s "moved into the secure store
  and erased".
- **A record already in the new layout is never displaced** (`REQ-UA-013`'s second scenario).

### The seam — KMPAuth for what it does, the SDK for what it cannot

`SupabaseAuthGateway` keeps its name, its file, its role and its allowlist entry. Its body
changes:

| Operation | Implemented with | Why |
|---|---|---|
| `signUp` | `SupabaseAuthBackend.signUp(email, password)` | KMPAuth's credential model |
| `signIn` | `SupabaseAuthBackend.signIn(AuthCredential.EmailPassword(email, password))` | KMPAuth's credential model |
| `signOut` | `SupabaseAuthBackend.signOut()` | — |
| the live session | `SupabaseAuthBackend.currentUser()`, `currentUserFlow()` | — |
| `sendMagicLink` | `auth.signInWith(OTP) { email; createUser = false }` | **KMPAuth's `sendSignInLinkToEmail` omits `createUser = false`** and would create a ghost account |
| `verifyMagicLink` | `auth.verifyEmailOtp(OtpType.Email.MAGIC_LINK, email, token)` | **KMPAuth's `signInWithEmailLink` parses `token_hash` / PKCE `code`**; the app's link is `singularity://verify?email=…&token=…` (`AuthLinkSpec.kt:27`) |
| `updatePassword` | `auth.updateUser { password = … }` | **KMPAuth has no password change** — `reauthenticate` is a fresh sign-in |
| `refresh` | `auth.refreshSession(refreshToken)` | **KMPAuth's `currentUserIdToken` returns only the access token** and cannot say "rejected" |

The backend is constructed per call from the already-resolved client, so there is exactly
one client and one session (`SupabaseClientProvider.kt:28-38`).

### The refresh — the answer that was unreachable

The current wrapper collapses every outcome into a failure:

```kotlin
// today — SupabaseAuthGateway.kt:115-126
runCatchingCancellable { auth.refreshSession(refreshToken) }.getOrNull()?.toRemote()
// …and then `call()` turns the null into a thrown error (:135-140)
```

So a rejected refresh token is indistinguishable from a network failure, and the repository
keeps the dead session (`AuthRepository.kt:388-396`). The rewrite drops the `call()` wrapper
for this one operation and keeps the two answers apart:

```kotlin
// after — a null session is the rejection, an exception is the transport failure
override suspend fun refresh(accessToken: String, refreshToken: String): Result<RemoteSession?> =
    try {
        Result.success(auth().refreshSession(refreshToken)?.toRemote())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e.toAppError())
    }
```

**This operation must not be wrapped in the shared `call()` helper.** The helper exists to
turn a null result into an error; here the null *is* the answer. A comment says so at the
call site, because the helper looks like the house style and the next reader will want to
use it.

### The seam's testability

The seam's entire SDK dependency is the `Auth` plugin — `clients.client()?.auth` at
`SupabaseAuthGateway.kt:41` and `:111` are its only uses of the client. `Auth` is an
**interface**, so the seam's dependency is substitutable.

The change adds one method to the existing provider and points the seam at it:

```kotlin
// core/auth/SupabaseClientProvider.kt
open suspend fun auth(): Auth? = client()?.auth
```

`SupabaseClientProvider` is already `open` with `open` methods precisely so a test can
substitute it (`SupabaseClientProvider.kt:67, 83, 98`). A test subclasses it and returns a
fake `Auth`. **No new production type, no new file, no allowlist change.**

The fake lives in the test source set. It implements the SDK's 42-member `Auth` interface:
three members meaningful, the rest stubbed. It is mechanical, and it breaks loudly and
cheaply when the SDK's interface changes — which is a cost this design accepts rather than
hides, and which the reviewer's alternative (below) avoids.

### The session load happens inside the restore's bound

The SDK's auth plugin reads its persisted session when the plugin initialises, which happens
while the client is being built (`SupabaseClientProvider.kt:101-110`) — and the client is
built by whoever asks first, which is not necessarily the restore. `model.md` names this:
the migration must not be able to hold the launch open.

So the plugin is configured with `autoLoadFromStorage = false`, and the seam's
`currentSession()` performs the load explicitly. The repository's restore is the first
caller in practice, so the load — and the migration it triggers — runs inside the restore's
bounded read.

## Alternatives considered

### A-1 — Keep `SecureSessionStore` and swap only the gateway's calls

**Lost.** The SDK would own a session *and* the app would own a second copy of the same
tokens. They diverge on the first refresh: the SDK rotates its tokens, the app's copy goes
stale, and the app keeps sending a token the provider has revoked. This is the failure
`REQ-UA-012` exists to prevent, and it would be introduced by the change meant to satisfy it.

### A-2 — Use KMPAuth's global `KMPAuth` facade and its Koin module

**Lost.** The facade is a process-global backend registry with its own Koin initializer
(`com.mmk.kmpauth.core.di.KMPAuthContextInitializer`). The app resolves its client lazily,
because "no project is configured yet" is a state a fresh install starts in
(`SupabaseClientProvider.kt:16-26`); a global registry binds a client at initialization and
cannot express "not configured". It also collides with the project's Koin rules — no
`includes()`, list composition only, and the compiler plugin verifying the graph. The backend
**class** (`SupabaseAuthBackend`) gives the credential model without any of that, so the
class is used and the facade is not.

### A-3 — Adopt `AuthProviderBackend` as the application's port, replacing `AuthGateway`

**Lost, and this is the closest call.** It is a real abstraction and it would remove the
project's own port. But it has no password change, no explicit-refresh-token contract, and
its `signInWithEmailLink` takes a link shape the app does not produce. More importantly, the
whole point of `AuthGateway` is that the domain sees `RemoteSession` and never a vendor type
(`AuthGateway.kt:18-31`); adopting a vendor-shaped port pushes the vendor's semantics into
`AuthRepository` and undoes the boundary the capability spec already requires (`REQ-UA-008`).

### A-4 — Reshape `Session.SignedIn` to drop its token fields

**Lost.** It is the honest shape once the SDK owns the session, and it is deferred rather
than rejected on principle. But it touches `core/sync` (six token readers) and the
`CurrentUser` fan-out (~82 files) for no behavioural gain, and the account-versus-session
comparison the contract depends on (`Session.kt:17-31`) is written in terms of those fields.
It is recorded as an open question, not done here.

### A-5 — Use KMPAuth for the three divergent operations too

**Lost, three times over.** `sendSignInLinkToEmail` would create a ghost account (D1);
`signInWithEmailLink` would not recognise the app's `?token=` link (D2);
`currentUserIdToken` would return only an access token and could not say "rejected" (D3).
Each is a contract difference, so each keeps the direct SDK call.

### A-6 — Prove the seam's three criteria against the local Supabase stack

**Lost, on availability.** The stack needs Docker, a compose file and a clone of a separate
infrastructure repository (`gazon1/supabase-repo-sing`) that is **not cloned on this host**,
plus a large upstream tarball. It is a manual recipe for "look at the result", not a test
fixture, and CI does not run it. The three criteria would be proven locally and unproven on
every pull request.

This is the alternative the reviewer should weigh against the fake: the fake costs one
42-member test file that breaks on an SDK bump; the stack costs a Docker daemon, a private
infrastructure repository and a `slow` tag, and buys coverage that CI does not run.

### A-7 — Drop the seam's testability and accept the three criteria as unverified

**Lost**, but it is the zero-cost option and the reviewer may prefer it. It is recorded in
`verification.md`'s `## Known limitations` and in this design's risks, so the choice is
explicit rather than accidental.

## Why this solution is the simplest

The application's provider abstraction already exists and already works: `AuthGateway` has
exactly one production implementation, and the domain sees only `RemoteSession`. So the
change is **a swap behind an existing port**, not an architecture.

Three arguments that this is the smallest sufficient solution rather than merely a small one:

**It introduces no new production type.** The port gains one method (`currentSession()`). The
provider gains one `open` method (`auth()`). The store is a new file because the old one is
deleted — the net count of production types does not rise. The seam's dependency is
substitutable because the SDK's `Auth` is an interface and the provider was already `open`.

**It deletes more than it adds.** `SecureSessionStore` (232 lines, four keys, a partial-write
undo, a one-shot migration) is replaced by one value, one write and a migration that reads
two sources instead of one. The credential store gets *smaller* while doing *more*.

**It keeps the domain untouched.** `Session`'s shape, `AuthRepository`'s interface,
`AuthDomain`'s validation, the magic-link URI template, the error codes, the Room schema and
the network API are all unchanged. That is what keeps `core/sync` and the 82-file fan-out out
of the change — and it is a direct consequence of keeping `RemoteSession` as the port's
currency rather than adopting a vendor-shaped one.

## Component and dependency changes

| Change | The specific defect it removes |
|---|---|
| `io.github.mirzemehdi:kmpauth-supabase:3.0.6` | The hand-written provider call sites for sign-up, sign-in and sign-out, and the absence of a credential model — the seam re-derives the SDK's API by hand at every call |
| `SupabaseAuthBackend` behind `AuthGateway` | The seam's three hand-rolled provider calls, each of which had to be re-derived when the SDK's API moved |
| `SecureSessionManager` — one value, one write | The four-key partial-write hazard (`SecureSessionStore.kt:72-105`): a write that fails part way can leave part of a session behind, and the code needs an undo path to prevent it |
| The SDK owning the session | The hand-written restore-time dance that reads four values and reconciles them into a session (`AuthRepository.kt:366-397`) |
| `AuthGateway.currentSession()` | The repository's need to know how a session is *stored* in order to read one — it reached into `SecureSessionStore` for four separate values |
| `SupabaseClientProvider.open suspend fun auth()` | **The vendor seam has no test at all.** Three acceptance criteria are about it, and `SupabaseClient` is not fakeable — the SDK's `Auth` plugin is |
| The refresh's null-versus-error mapping | **`REQ-UA-003`'s signed-out state is unreachable in production.** The shared `call()` wrapper collapses the rejection into a failure, so a dead session is kept and every later request is answered "unauthorised" |
| The confinement gate scanning `com.mmk.kmpauth` | The gate would measure nothing for the second vendor — the "green and checking nothing" failure the test's own comment warns about (`VendorSdkConfinementTest.kt:51-58`) |
| `BackupRulesTest` | `REQ-UA-015`'s exclusion scenario had no test; a new key in a new file could have silently lost the exclusion |
| `SupabaseClientProvider`'s `autoLoadFromStorage = false` | The SDK loading its session while the client is being built — by the sync engine, by the backup service, by anyone — which is outside the restore's bound and can hold the launch open |

## State-model changes

The **session machine** keeps its four states and its transitions. What changes is that one
transition gains a distinction that was unreachable: `refreshOutcome(rejected)` becomes a real
outcome, and the repository's sign-out branch (`AuthRepository.kt:377-383`) becomes reachable
for the first time in production.

The **credential-store machine** is new, and its states are `Empty`, `Present(session)`,
`Present(tokenOnly)`, `LegacyComplete`, `LegacyPartial` and `Plaintext` — with the migration
transitions that move a record from a legacy source into the current one. `model.md` holds
the full table; the design adds no state beyond it.

The new `Present(tokenOnly)` state deserves a note, because it looks like a new idea and is
not: it is the state today's devices are already in, produced by the old plaintext migration
(`SecureSessionStore.kt:151-155`) — tokens without an owner id, which the restore cannot
use. The change preserves that state and its outcome rather than inventing a policy for it.

## Persistence and compatibility impact

**The credential store's representation changes.** Four keys become one. The migration moves
a record out of either legacy source and erases it afterwards. **A session already on the
device must be moved, not discarded** — that is `REQ-UA-023`, and it is the one genuinely new
observable guarantee this change introduces.

| Surface | Impact |
|---|---|
| The keychain | One new key holding one value. The four previous keys are erased after the migration. The file itself is unchanged, so the backup exclusion still holds (`backup_rules.xml`, `data_extraction_rules.xml`) |
| Plain-text preferences | Unchanged — the legacy token slots become read-only migration sources, and the local owner id, the device id and the local-session marker stay exactly where they are |
| The Room schema | Unchanged. No `SCHEMA_VERSION`, no `AutoMigration` |
| `Session.SignedIn` | Unchanged — id, email, access token, refresh token. The 82-file fan-out and `core/sync`'s six token readers are untouched |
| `AuthRepository`'s interface | One method added (`currentSession()` on the port, not on the repository) |
| The error codes | Unchanged, including `auth.session_not_stored` and `auth.no_session` |
| The magic-link URI | Unchanged — `singularity://verify?email=…&token=…` |
| The debug session stub | Retargeted: it writes the session through the new store, so the Maestro flow that corrupts the store still has a real session to corrupt |

## Testing strategy

`verification.md` designs 30 scenarios; this design does not change them. What the design
adds is **where the seams are** and **what the fake costs**.

- **The store** (`SecureSessionManagerTest`, new) — unit level, `fast`, over the existing
  `MapSecureStorage` fake. It needs no SDK involvement: the SDK's `SessionManager` interface
  has three methods, and the fake the tests need is the app's own `SecureStorage`.
- **The seam** (`SupabaseAuthGatewayTest`, new) — unit level, `fast`, over a fake `Auth`.
  The fake captures the configure lambda the seam passes to `signInWith`, applies it to a real
  `OTP.Config`, and reads `createUser` off it — so `createUser = false` is *observed*, not
  assumed. The fake implements the SDK's 42-member `Auth` interface: three members
  meaningful, the rest stubbed.
- **The repository** (`AuthRepositoryTest`, existing) — unit level, `fast`, over the existing
  `ScriptedGateway`, which already counts `signOutCalls` — the assertion AC-07 needs.
- **The gate** (`VendorSdkConfinementTest`, `BackupRulesTest`) — unit level, `fast`.
- **The device** — the existing Maestro flow `12-unreadable-store-recovers.yaml` is the only
  end-to-end proof that the store's layout is really on the device. It is not a new flow: it
  installs its session through the debug stub, so the stub's retarget is what keeps it
  meaningful.

**The fake's cost, stated plainly:** one test-source file that implements 42 interface
members and must be updated when the SDK's `Auth` interface changes. The compiler says so
immediately and the fix is one line per new member. This is the price of three acceptance
criteria being provable at all; the alternative is `verification.md`'s option B or C.

## Rollout or migration

There is no feature flag and no staged rollout: the change is internal, and the observable
behaviour is either unchanged or a fix.

**The rollout is the migration**, and it happens on the device that already holds a session:

1. The first launch after the update reads the new key — absent.
2. It reads the previous representation's four values. If they carry an owner id, the record
   is a session; if they do not, it is a token-only record.
3. It writes the record into the new key **first**.
4. It erases the previous values **second**.
5. If step 3 failed, nothing was erased, and the next launch retries from step 1.
6. If step 4 failed, the record is already in the new key, and the next launch erases the
   leftover without importing it.

**An interrupted update leaves the session in at least one place** — the crash window is
between steps 3 and 4, and the ordering is chosen so that window holds a usable session
rather than none. **A leftover never displaces a newer session**: the new key is read first
and wins.

**The plaintext source** is consulted only when the keychain has nothing, and it is erased
only after the write succeeded.

## Risks and mitigations

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| **R-1** | **Binary compatibility.** `kmpauth-supabase:3.0.6` is published against `auth-kt:3.7.0`; the application pins `supabase = "3.8.0"` (`libs.versions.toml:79`) and the cache resolves `auth-kt-jvm-3.8.0`. Gradle takes the higher version, so KMPAuth's pre-compiled calls into the SDK may not bind — a `NoSuchMethodError` at run time, not at compile time. | **High** | The first implementation task resolves the dependency and exercises the three KMPAuth-backed operations against the real SDK. If a signature differs, pin `auth-kt` to `3.7.0` and re-check the app's own SDK usage. **Settled before any other work.** |
| **R-2** | The fake `Auth` (42 members) breaks on every SDK bump that touches the interface. | Medium | It is one file; the compiler names the missing member. The alternative — accepting the three criteria as unverified — is `verification.md`'s option C and is the reviewer's to pick |
| **R-3** | The SDK's `Auth` plugin writes the session somewhere else as well, so the app's copy diverges. | Medium | The seam owns the only `SessionManager` the client is built with (`install(Auth) { sessionManager = … }`), and `autoLoadFromStorage = false` keeps the SDK from loading from anywhere else. Verified by the store's own tests |
| **R-4** | The SDK refreshes on its own (`checkSessionOnRequest`) and republishes a session the app did not expect, breaking the account-versus-session comparison. | Medium | The flags are set explicitly at client construction and the publication order is pinned by `AuthRepositoryTest` |
| **R-5** | The migration runs outside the restore's bound, because something else built the client first. | Medium | `autoLoadFromStorage = false`, and the load is triggered by the seam's `currentSession()`, which the restore calls first |
| **R-6** | KMPAuth's Supabase backend is young (3.0.x) and its own defect history is unknown to us. | Low | The app's `AuthGateway` port isolates it: a defect is fixed in one seam file, and the port can be re-implemented without touching the domain |
| **R-7** | The change is described as "using KMPAuth" while three operations still call the SDK directly. | Low | Stated in this design, in `investigation.md`'s divergence table and in the ADR. The commit message must not claim a full swap |
| **R-8** | The Maestro session stub is not retargeted, and flow 12 silently proves nothing. | Low | The flow waits for `nav_tab_today`, so a stub that writes nothing makes it **fail**, not pass quietly. Retargeting the stub is an explicit task |

## Task dependencies

```
Phase 1 — foundation (parallel, disjoint files)
  T001 ADR + MODULE-INDEX      T002 dependency        T003 AuthGateway port
  T004 SecureSessionManager     T011 BackupRulesTest
        │
        ▼
Phase 2 — the seam's plumbing
  T005 SupabaseClientProvider   (needs T004)
  T012 SecureSessionManagerTest (needs T004)     ← parallel with T005
        │
        ▼
Phase 3 — the seam and the repository (parallel with each other)
  T006 SupabaseAuthGateway      (needs T002, T003, T005)
        │
        ├─▶ T007 AuthRepository  (needs T003, T006)
        ├─▶ T010 confinement gate (needs T004, T006)
        └─▶ T013 gateway test    (needs T006)
        │
        ▼
Phase 4 — wiring and cleanup
  T008 Koin rebinding   (needs T005, T006, T007)
  T014 repository tests (needs T007)                    ← parallel with T008
        │
        ▼
  T009 delete the old store, retarget the stubs (needs T008)
        │
        ├─▶ T015 the auth feature's tests  (needs T009)
        └─▶ T016 the docs row              (needs T009)
        │
        ▼
Phase 5 — the gates
  T017 the full gate run (needs everything)
```

Two `[P]` groups exist, and no two parallel tasks touch the same file.

## Non-goals

- **Changing which provider issues the sessions.** Supabase remains the issuer; only the code
  that talks to it changes.
- **Signing in with an external identity.** `REQ-UA-001` keeps email and password as the only
  way in. Adding a second way is a capability change of its own.
- **Reshaping the session type.** Deferred, with the reason recorded above.
- **Making the offline session a provider-side identity.** ADR
  `2026-10-05-anonymous-is-local-only-and-signing-in-offers-a-choice` decided that, and this
  change does not reopen it.
- **The Google calendar sign-in.** It already uses KMPAuth, through a different provider, and
  this change does not touch it.
- **Resurrecting a legacy plain-text session over the network.** It is not restorable today
  (no owner id), and making the restore reach the provider to rebuild one changes what the
  restore is.
- **A capability spec for the credential store as its own capability.** The observable
  token-storage contract already has an owner — `user-authentication`'s `REQ-UA-002`,
  `REQ-UA-012` and `REQ-UA-013` — and a second capability would give one behaviour two
  owners.

## Validation commands

```bash
# The dependency resolves and the SDK calls bind (R-1) — run this first.
./gw :shared:compileKotlinJvm
./gw :shared:dependencyInsight --configuration jvmRuntimeClasspath --dependency auth-kt

# The store's scenarios.
./gw :shared:jvmTest --tests 'SecureSessionManagerTest'

# The seam's scenarios.
./gw :shared:jvmTest --tests 'SupabaseAuthGatewayTest'

# The repository's scenarios.
./gw :shared:jvmTest --tests 'AuthRepositoryTest'
./gw :shared:jvmTest --tests 'AuthViewModelTest'
./gw :shared:jvmTest --tests 'SignOutKeepsLocalDataTest'
./gw :shared:jvmTest --tests 'LocalAccountClaimerTest'

# The gates.
./gw :shared:jvmTest --tests 'VendorSdkConfinementTest'
./gw :shared:jvmTest --tests 'PlaintextTokenIsolationTest'
./gw :shared:jvmTest --tests 'BackupRulesTest'

# The whole suite and the change's own gates.
just jvm
just lint-changed
just os-validate
just gm auth          # the device flows, on an emulator
```

`./gw` is the repository's own Gradle wrapper and gives the worktree a private
`GRADLE_USER_HOME`; `AGENTS.md` requires it over `./gradlew`. `spec.md`'s `Verification:`
lines name `./gradlew` — the commands are equivalent, and these are the ones to copy.

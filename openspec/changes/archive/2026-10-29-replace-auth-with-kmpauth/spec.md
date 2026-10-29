# Spec — Replace the home-grown auth adapter with KMPAuth's Supabase backend

> Written by `speckit-engineering-specify`. This document states **what must be true**,
> never **how to make it true**. Choosing between a `Mutex`, an actor-like queue and a
> transactional lock is `design.md`'s decision, unless `investigation.md` already recorded
> a constraint that forbids the alternatives.
>
> Sections marked *(when applicable)* stay in the document. Mark them *Not applicable*
> with a reason — do not delete them. Cancellation, concurrency and persistence are where
> this codebase's real bugs live.

## Summary

The app authenticates through a hand-written adapter over one vendor SDK and a
hand-written credential store, while the contract already asks for a replaceable
identity provider. This change replaces both with KMPAuth's Supabase backend and an
SDK-owned session persisted through the app's existing secure-storage port, without
changing what any user or observer can see — except to make one behaviour the contract
has always required reachable in production: a session the provider can no longer
refresh ends the sign-in.

## Problem

Two problems. The second is a behavioural defect; the first is the reason this change
exists.

**P-1 — The provider abstraction is shaped like one vendor.** The contract requires the
identity provider to be replaceable (`REQ-UA-008`), and a port exists to satisfy it.
But everything behind that port — the adapter, the credential store's layout, the
refresh policy, the shape of "a session" — is written against one vendor's API rather
than against the port. The provider is replaceable in the domain and concrete in
practice, so every change to the vendor's API is a hand-rewrite of the adapter, and the
session representation the rest of the app depends on is one vendor's representation
wearing a project type.

**P-2 — A session that can no longer be refreshed does not end the sign-in.** The
contract says the system moves to a signed-out state when the session can no longer be
recovered (`REQ-UA-003`). In production it does not: the gateway's refresh operation
cannot return the "rejected" answer that the repository reads as "sign out", so a
rejected refresh token is handled exactly like a network failure — the stored session is
kept, the app reports a signed-in user, and every later request is answered with an
unauthorised error that nothing clears. The branch in the repository that would sign the
user out is exercised only by the test double.

## Scope

**In:**

- The adapter that speaks to the identity provider, and the credential store that holds
  the session — replaced, not extended.
- The session restore path, which currently reconciles four separately stored values.
- The static guard that keeps vendor types out of the domain and feature layers.

**Out:**

- The identity provider itself. The same provider issues the sessions; only the code
  that talks to it changes.
- The offline session, the ownership transfer, the account switcher, and every screen.
- The room schema, the sync engine, and the network API.

**A capability delta is required, and it modifies an existing capability.** The
observable contract of the credential store is already owned by `user-authentication`
(`REQ-UA-002` — tokens live in the secure store and not in plain-text preferences;
`REQ-UA-012` — the store holds no incomplete session; `REQ-UA-013` — a plain-text token
is moved into the store and erased). This change alters that capability in two ways: the
architecture guard behind `REQ-UA-008` must now cover every vendor SDK the application
depends on rather than one, and the update path needs a guarantee it does not have yet
(`REQ-UA-023`). The delta is in
`openspec/changes/replace-auth-with-kmpauth/specs/user-authentication/spec.md`.

The candidate id `core/auth-token-storage` (`openspec/specs/MODULE-INDEX.md:43`) was
**not** taken. That capability would restate `REQ-UA-002`, `REQ-UA-012` and
`REQ-UA-013` under a second name, giving one observable behaviour two owners and two
places to update whenever it changes.

## Non-goals

- **Changing which provider issues sessions.** The delta is about who writes the code
  that talks to the provider, not about the provider.
- **Signing in with an external identity.** The contract specifies email and password
  (`REQ-UA-001`), and this change keeps that the only way to sign in. Adding a second
  way is a capability change of its own.
- **Reshaping the session type.** The session carries fields the rest of the app reads;
  changing its shape is a separate change with a much wider blast radius.
- **Making the offline session a provider-side identity.** The offline session is local
  by decision (ADR `2026-10-05-anonymous-is-local-only-and-signing-in-offers-a-choice`).
- **Resurrecting a legacy plain-text session over the network.** See Open questions.

## Current behavior

Facts, cited to `investigation.md` or to `path:line`.

- The port is `AuthGateway` — seven operations in project vocabulary, all returning
  `Result<T>` over `RemoteSession` (`core/auth/AuthGateway.kt:33-76`, `:86-92`). It has
  exactly one production implementation (`core/auth/AuthGateway.kt:155`).
- That implementation calls the vendor SDK directly for every operation
  (`core/auth/SupabaseAuthGateway.kt:29-161`).
- The session is stored as four separately written values — access token, refresh token,
  email, owner id — with a partial write undone and a one-shot migration from
  plain-text preferences (`core/auth/SecureSessionStore.kt:42-232`).
- The restore path reads those four values and then refreshes
  (`core/auth/AuthRepository.kt:366-397`); the local-session fallback is `:405-422`.
- The refresh operation cannot produce the "rejected" answer the repository reads as
  "sign out": the inner capture collapses both a rejection and a transport error to null
  (`core/auth/SupabaseAuthGateway.kt:123-125`), and the wrapper turns a null result into
  a thrown error (`:135-140`), so every outcome is a failure. The repository's
  sign-out branch (`core/auth/AuthRepository.kt:377-383`) is therefore unreachable with
  the production gateway.
- The store is a single file excluded from backup and device transfer, because its
  encryption key cannot be restored (`androidApp/src/main/res/xml/backup_rules.xml`,
  `androidApp/src/main/res/xml/data_extraction_rules.xml`).
- The architecture guard allows exactly six files to import the vendor SDK and requires
  each to still import it (`shared/src/jvmTest/.../arch/VendorSdkConfinementTest.kt:92-143`).
- A credential-store failure ends the provider-side session, best-effort
  (`core/auth/AuthRepository.kt:499-542`) — the contract's requirement, already met.

## Required behavior

Decisions, not facts.

- The adapter behind the port SHALL be implemented against a provider-agnostic backend
  that wraps the same client the app already builds, so that exactly one client and one
  session exist.
- The session SHALL be persisted by the identity SDK, through the app's existing
  secure-storage port, so that the storage location and its backup exclusion are the
  ones already in force.
- The credential store SHALL hold the session as one value, so that a write which fails
  cannot leave part of a session behind.
- A session already held in the store SHALL survive an update that changes how the
  store represents a session; the update SHALL move it rather than discard it, and
  SHALL NOT leave it in neither representation.
- A rejected refresh token SHALL end the session and move the app to a signed-out
  state. A transport failure SHALL NOT.
- The plain-text token in preferences SHALL still be moved into the store and erased, and
  SHALL still not be able to displace a session the store already holds.
- The offline session SHALL continue to contact no provider.
- No vendor type SHALL reach the domain or feature layers, and the static guard SHALL
  cover every vendor SDK the application depends on.

## Invariants

### INV-01 — No token is ever written to plain-text preferences
A refresh token is a bearer credential for the user's account: anyone who can read the
file can mint an access token and act as the user. A file read is enough, and a
preferences file is readable by anyone who can read the app's data directory.

### INV-02 — The credential store holds at most one complete session, and never a partial one
A later launch that finds some of a session's fields reads it as a signed-in owner the
fields disagree about, and every row written afterwards is attributed to an owner that
does not exist. Half a session is worse than none: none reads as signed out.

### INV-03 — A migration never leaves a session in neither place
The move is two steps — write the new place, erase the old — and a crash between them
must leave the session in at least one. The reverse order has a window in which the
session is in the place being emptied and already gone from the one being filled, and
the user is signed out of their own account with no way back.

### INV-04 — A migration never replaces a newer session with an older one
A refresh token that was rotated on the way out is usually already revoked, so
importing an older copy over a newer one yields a sign-in that works once and then
fails, and the user is signed out of a session that was sitting in the store the whole
time.

### INV-05 — The domain and feature layers never see a vendor auth type
A vendor SDK is not a utility: it arrives with a session, a connection pool, a retry
policy and a serialiser, and each of those is reasonable to reach for. A type in the
domain model makes a version bump a migration.

### INV-06 — A rejected refresh token ends the session; a transport failure never does
They are different facts about the world. The first says the credential is dead; the
second says the device could not ask. Signing the user out because they were on a
plane loses their session, and keeping a dead one because a request timed out leaves
them permanently signed in and permanently failing.

### INV-07 — The offline session contacts no provider
"Continue Offline" is offered exactly when there is no network or no configured
project. Making it a provider call turns it into a network operation wearing an
offline label.

### INV-08 — Signing out never discards locally queued changes
The outbox is the user's unsent work. It survives being signed out and is delivered
after the next sign-in; discarding it on sign-out loses work the user can see and
cannot recover.

### INV-09 — A credential-store failure ends the provider-side session
The provider issued a session the device then failed to store. Nothing will ever
revoke it, because the refresh token is gone and no later request carries it. The
failure is best-effort and must not replace the storage failure as the answer to
"why am I not signed in".

## Failure semantics

One row per failure the model has.

| Failure | State left behind | Recoverable | The caller observes |
|---|---|---|---|
| The store write fails | Nothing in the store | Yes — sign in again | A failed attempt naming the local cause, never the credentials; no session left at the provider |
| The store cannot be read (an undecryptable store) | Nothing | Yes — sign in again | A signed-out session after the bound, and one restore-failure report |
| The store never answers | Nothing | Yes — sign in again | A signed-out session after the bound, and one restore-failure report |
| The migration's write fails | The plain-text copy stays | Yes — the next launch retries | A signed-out session |
| The migration's erase fails | The session is present in the new place, the old copy still there | Yes — the next launch erases it | A signed-in session; the leftover never replaces it |
| The refresh token is rejected | The session is cleared | No | A signed-out session |
| The refresh fails in transport | The stored session is kept | Yes | A signed-in session holding the stored token |
| The provider is unreachable at sign-in | Nothing | Yes | A failed attempt with a network cause |
| The provider is unreachable at sign-out | The local session is cleared anyway | Yes | A signed-out session |
| The restore exceeds its bound | Nothing | Yes | A signed-out session, and one restore-failure report |

## Cancellation and lifecycle

*(when applicable — this change has a scope, a deadline and a one-shot migration, so
the section applies rather than being skipped)*

- The restore runs under a bounded read, and the bound covers the credential-store read
  **and** the migration it may trigger. A migration that is not covered by the bound
  would hold the launch open for as long as the store takes to answer.
- The one-shot migration guard is per process. A cancellation **before** the guard is
  set leaves the plain-text copy in place, so the next launch retries it; a
  cancellation after the guard is set leaves the session where it was written.
- The session is persisted before it is published, and that order is preserved: a
  collector that reacts to the new session by reading a token must find the token
  already there, or its first request goes out unauthenticated
  (`core/auth/AuthRepository.kt:545-563`).
- The repository's work runs on the injected scope. The store's own writes are bounded
  by the same restore deadline, and a cancelled write leaves nothing behind, because
  the session is one value.

## Concurrency and ordering

*(when applicable)*

- A save racing a delete must not resurrect a deleted session. The store serialises
  its writes.
- The migration runs at most once per process, and two concurrent first reads must not
  both migrate: one performs the move, the other observes the result.
- Ordering: the session is persisted, then published. A published session that is not
  yet stored is a session whose token a reader may not find.
- The restore's deadline is the outermost bound; nothing inside it may extend it.

## Persistence and recovery

*(when applicable)*

- **The credential store's representation changes.** The session becomes one value
  instead of four keys. That is an internal detail; the guarantee that a stored
  session survives an update is not (INV-03, `REQ-UA-023`).
- **A session already in the previous representation is moved, not discarded.** The
  move writes the new value first and erases the old keys second. If the new value is
  already present, the old keys are erased rather than imported (INV-04).
- **The plain-text source is still read once and erased**, under the same ordering.
- **The store file stays excluded from backup and device transfer**, and the new value
  lives in that same file, so the exclusion continues to hold without a change.
- **The room schema is unchanged.** No `SCHEMA_VERSION`, no `AutoMigration`.
- The operation is idempotent: a repeated launch after a successful migration finds the
  session in the new place and does nothing.

## Compatibility constraints

- The session type's shape is unchanged. The identity the session carries, its email,
  its access token and its refresh token are all read by code far from the auth
  feature, and the contract's own note on comparing accounts rather than sessions
  (`core/auth/Session.kt:17-31`) depends on the access token and refresh token
  rotating together while the identity stays the same.
- The repository's public interface is unchanged; no caller outside the auth feature
  sees the change.
- The address normalisation and the length bounds are unchanged.
- The magic-link URI template is unchanged
  (`core/auth/AuthLinkSpec.kt:27`), so links already in users' inboxes still open the
  right screen.
- The error codes are unchanged, including the one a credential-store failure reports.
- The room schema, the sync engine's wire format and the MCP server's tool surface are
  unchanged.
- Android and JVM desktop only; there is no iOS target.

## Acceptance criteria

- [ ] **AC-01:** When a user signs in and the session is persisted, the credential
      store SHALL hold the session and the plain-text preferences SHALL hold no token.
      `Verification:` `SecureSessionManagerTest::a session is kept in one value and read back`
      — `./gw :shared:jvmTest --tests 'SecureSessionManagerTest'`
- [ ] **AC-02:** When the application is updated and the store's representation has
      changed, the session that was already stored SHALL be usable and the user SHALL
      NOT be asked to sign in again.
      `Verification:` `SecureSessionManagerTest::the previous layout is migrated and remains usable`
      — `./gw :shared:jvmTest --tests 'SecureSessionManagerTest'`
- [ ] **AC-03:** When a plain-text token is found in preferences, it SHALL be moved into
      the credential store and erased, and it SHALL NOT displace a session the store
      already holds.
      `Verification:` `SecureSessionManagerTest::a plain-text token is moved into the store and erased`
      — `./gw :shared:jvmTest --tests 'SecureSessionManagerTest'`
- [ ] **AC-04:** When writing the session to the credential store fails, the store
      SHALL afterwards hold no session at all, and the attempt SHALL be reported as a
      failure naming the local cause.
      `Verification:` `SecureSessionManagerTest::a failed write leaves no session behind and names the local cause`
      — `./gw :shared:jvmTest --tests 'SecureSessionManagerTest'`
- [ ] **AC-05:** When the provider rejects the stored refresh token, the session SHALL
      become signed out.
      `Verification:` `AuthRepositoryTest::a session the server refuses to refresh signs the user out`
      — `./gw :shared:jvmTest --tests 'AuthRepositoryTest'`
- [ ] **AC-06:** When the refresh fails because the device could not reach the
      provider, the stored session SHALL be kept.
      `Verification:` `AuthRepositoryTest::a refresh that fails on the network keeps the stored session`
      — `./gw :shared:jvmTest --tests 'AuthRepositoryTest'`
- [ ] **AC-07:** When the device fails to store an issued session, the session SHALL be
      ended at the provider.
      `Verification:` `AuthRepositoryStoreFailureTest::the session the device could not store is ended at the provider`
      — `./gw :shared:jvmTest --tests 'AuthRepositoryStoreFailureTest'`
- [ ] **AC-08:** When a magic link is sent to an address that has no account, no
      account SHALL be created.
      `Verification:` `SupabaseAuthGatewayTest::a magic link to an unknown address creates no account`
      — `./gw :shared:jvmTest --tests 'SupabaseAuthGatewayTest'`
- [ ] **AC-09:** When a magic-link token is exchanged, a live session SHALL result.
      `Verification:` `SupabaseAuthGatewayTest::a magic link token is exchanged for a session`
      — `./gw :shared:jvmTest --tests 'SupabaseAuthGatewayTest'`
- [ ] **AC-10:** When a sign-up yields no session because the address needs
      confirmation, the outcome SHALL be reported as awaiting confirmation rather than
      as a failure, and the application SHALL NOT enter as signed in.
      `Verification:` `AuthViewModelTest::a sign-up awaiting confirmation says what to do, and not as an error`
      — `./gw :shared:jvmTest --tests 'AuthViewModelTest'`
- [ ] **AC-11:** When the user chooses to continue without an account, no request SHALL
      be made to any identity provider and no provider-side identity SHALL be created.
      `Verification:` `AuthRepositoryTest::the local session contacts no provider at all`
      — `./gw :shared:jvmTest --tests 'AuthRepositoryTest'`
- [ ] **AC-12:** When the restore cannot be completed, the session state visible to the
      rest of the application SHALL be signed out, and the failure SHALL be reported
      once.
      `Verification:` `AuthRepositoryRestoreBoundTest::a restore that fails is answered the same way as one that misses the deadline`
      — `./gw :shared:jvmTest --tests 'AuthRepositoryRestoreBoundTest'`
- [ ] **AC-13:** When the signed-in identity is published, every consumer SHALL receive
      that identity unchanged.
      `Verification:` `AuthRepositoryTest::signing in publishes the session and stores the tokens`, `ReadToolsProfileAwareTest::list_tasks_uses_profile_scoped_userId_when_userId_is_blank`
      — `./gw :shared:jvmTest --tests 'AuthRepositoryTest' --tests 'ReadToolsProfileAwareTest'`
- [ ] **AC-14:** When the source of every production file is scanned, no file outside
      the declared seams SHALL import a vendor SDK, and the rule SHALL still be able to
      fail.
      `Verification:` `VendorSdkConfinementTest::only the declared seams import a vendor sdk`
      — `./gw :shared:jvmTest --tests 'VendorSdkConfinementTest'`
- [ ] **AC-15:** When the credential store's file is inspected, it SHALL be excluded
      from both backup and device transfer.
      `Verification:` `BackupRulesTest::the secure store is excluded from cloud backup`, `BackupRulesTest::the secure store is excluded from device transfer as well`
      — `./gw :shared:jvmTest --tests 'BackupRulesTest'`
- [ ] **AC-16:** When the whole suite and the gates run, every check SHALL pass.
      `Verification:` `AuthRepositoryTest` — `just jvm && just lint-changed && just os-validate`

**Rules observed for this list:** every criterion names a resolvable test id and the
command that runs it; no number is reused; each criterion asserts something observable
(a stored value, a published session, a report, a scanned file). `AC-13`, `AC-14` and
`AC-16` name tests that already exist or already run; the rest are designed by
`verify-design` and written by `implement`.

## Open questions

1. **`openspec/specs/MODULE-INDEX.md:43` names a candidate id this change does not
   take.** The row lists `core/security`, `core/auth` as not covered, with
   `core/auth-token-storage` as the candidate. This change amends `user-authentication`
   instead, because that capability already owns the observable token-storage
   behaviour (REQ-UA-002, REQ-UA-012, REQ-UA-013) and a second capability would give
   one behaviour two owners. The row should be amended to record that the candidate was
   not taken and where the behaviour is actually specified — **resolved in this change**,
   in the same commit as the delta, so the index does not claim a gap that no longer
   exists.
2. **Should a legacy plain-text session be resurrected over the network during the
   restore?** The plain-text copy holds tokens but no owner id, and the restore needs
   both, so today it ends signed out. A refresh would recover the owner id, so the
   session is theoretically recoverable — but the store holds tokens, not a network
   call, and making the restore reach the provider to rebuild a session changes what
   the restore is. **Out of scope here**, and deliberately so: it is a behaviour change
   to a path that already works as the contract describes, and it would need its own
   requirement.
3. **Does the dependency version resolve?** The provider backend is published against a
   different version of the SDK than the application pins. Whether the two are
   compatible is a dependency question with no behavioural content, and it is settled
   by the first task of the implementation rather than by a requirement.

## References

- `openspec/specs/user-authentication/spec.md` — the capability this change amends;
  REQ-UA-001 … REQ-UA-022.
- `openspec/specs/MODULE-INDEX.md:4-12, 43` — the coverage rule, and the candidate id
  this change does not take.
- ADR `2026-10-05-anonymous-is-local-only-and-signing-in-offers-a-choice` — the local
  session is local.
- ADR `2026-09-27-di-module-aggregator-narrative` — the DI rules the change must not
  break.
- ADR `2026-09-05-secret-storage-split` — the split that produced the credential store.
- `core/auth/AuthGateway.kt` — the port and the session type.
- `core/auth/AuthRepository.kt` — the repository, the restore path, the four rules.
- `core/auth/SupabaseAuthGateway.kt` — the adapter being replaced.
- `core/auth/SecureSessionStore.kt` — the store being replaced.
- `core/auth/Session.kt:17-31` — why the account, not the session, is compared.
- `core/auth/SupabaseClientProvider.kt` — the client seam.
- `feature/auth/AuthLinkSpec.kt:27` — the magic-link URI template.
- `shared/src/jvmTest/.../arch/VendorSdkConfinementTest.kt` — the guard.
- `androidApp/src/main/res/xml/backup_rules.xml`,
  `androidApp/src/main/res/xml/data_extraction_rules.xml` — the store's exclusion.
- `investigation.md` — the facts, hypotheses and reproduction this spec was built on.

---

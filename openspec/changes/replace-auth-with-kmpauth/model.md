# Model — Replace the home-grown auth adapter with KMPAuth's Supabase backend

> Written by `speckit-engineering-model`. The transition table's columns deliberately mirror jqwik's
> state-machine model (`Action`, precondition, `Transformer.mutate`, `withInvariant`), so that
> migrating to property-based testing later is mechanical rather than a rewrite.
>
> **This step always produces a file.** Either a model, or a justified *Not applicable*. An empty
> document, or a table invented to fill the template, is a worse outcome than an honest omission.

## Purpose and scope

Two state machines are modelled, because this change alters both.

The **session machine** is what the application believes about who it is acting as. It exists
today and this change alters two of its transitions — the refresh outcome and the sign-out
effect — without adding a state.

The **credential-store machine** is what the device holds in order to reproduce that identity
on the next launch. This change replaces its representation, so it gains states and gains an
entirely new transition family: the migration of an existing session into the new
representation.

The four state tests all come back **yes**: a value persists across calls (the stored
session); the same event can arrive twice with a different outcome (the migration runs once;
a refresh behaves differently depending on what is stored); work can be interrupted and
retried (the bounded restore, the migration's write-then-erase ordering); and two callers
can interleave (a store write racing a store delete).

Deliberately **outside** the boundary: the provider's own session model, the address and
password validation, the ownership transfer's row-level atomicity, and the sync engine's
state. The first three are unchanged by this change; the fourth is a different component
with its own model.

## Model boundary

**Inside:** the published session (`core/auth/AuthRepository.kt`'s `_currentSession`) and the
credential store's content (`core/auth/SecureSessionStore.kt` today, its replacement
tomorrow).

**Outside:** everything the provider does with a session between the two — the token
rotation, the refresh, the sign-out call. Those are modelled as **events with outcomes**,
because what matters here is how their outcome is interpreted, not how they work.

The boundary matters for one specific reason: the provider SDK owns the live session after
this change, so the "store" side of the model is driven by the SDK rather than by the
repository. That is a change of *who raises the events*, not of *what the states are*.

## States

### The session machine

| State | Meaning | Reachable from |
|---|---|---|
| `Loading` | The identity is not known yet; the restore has not finished. | initial |
| `SignedOut` | No account and no local session. | `Loading`, `SignedIn`, `Anonymous` |
| `SignedIn(userId, email, accessToken, refreshToken)` | An account session is in force. | `Loading`, `SignedOut`, `Anonymous` |
| `Anonymous(userId)` | The local, provider-free session. | `Loading`, `SignedOut` |

`Loading` is the repository's initial value (`core/auth/AuthRepository.kt:167`) and is
assigned nowhere else; it is left only by the restore, which is bounded, so the application
cannot be left in it.

### The credential-store machine

| State | Meaning | Reachable from |
|---|---|---|
| `Empty` | The store holds no record. | initial, `Present` after a delete, `Empty` after a failed write |
| `Present(session)` | The store holds one record in the current representation, with an owner id. | `Empty` after a write or a migration, `Present` after a save |
| `Present(tokenOnly)` | The store holds one record in the current representation with tokens but no owner id — a migrated legacy record, which is **not** a session. | `Empty` after a migration from a legacy source |
| `LegacyComplete` | The store holds a session in the previous representation: a refresh token and an owner id among the four separate values. | `Empty` after an update that changed the representation |
| `LegacyPartial` | The store holds some of the previous representation's values, including the three the old plaintext migration wrote — tokens and an email, but no owner id. | `LegacyComplete` after an interrupted write in the previous version, or after the old plaintext migration |
| `Plaintext` | Preferences hold a plain-text token and the store holds nothing. | `Empty` after an update from a version that stored tokens in preferences |

`Present(tokenOnly)` is not a new shape. The old store's plaintext migration wrote exactly
three values — access token, refresh token and email — and no owner id
(`core/auth/SecureSessionStore.kt:151-155`), because the plain preferences never held one
(`core/auth/DataStoreSessionStore.kt:47-58`). A device that ran that migration is, today, in
`LegacyPartial`, and the restore finds no owner id and ends signed out
(`core/auth/AuthRepository.kt:367-372`). This change preserves that outcome and preserves
the tokens, moving them out of plain text as the contract requires.

## Events and triggers

| Event | Raised by |
|---|---|
| `restore()` | the repository, on launch |
| `signIn(email, password)` | the auth screen |
| `signUp(email, password)` | the auth screen |
| `verifyMagicLink(email, token)` | the deep-link handler |
| `startLocalSession()` | the "Continue Offline" action |
| `signOut()` | the account screen |
| `refreshOutcome(rejected \| transportFailure \| refreshed)` | the provider, during the restore or on a token rotation |
| `providerSessionChanged(session)` | the provider SDK, when the live session changes |
| `storeLoad()` | the provider SDK's auth plugin, when it reads the persisted session |
| `storeSave(session)` | the provider SDK's auth plugin, after an authentication |
| `storeDelete()` | the provider SDK's auth plugin, on sign-out |

## Transition table

### The session machine

| Source | Event / trigger | Guard | Target | State mutation | Invariants checked | Side effects | Failure behaviour | Cancellation behaviour | Verification reference |
|---|---|---|---|---|---|---|---|---|---|
| `Loading` | `restore()` completes with a stored session | the store yields a record with an owner id | `SignedIn` | publish `Session.SignedIn` with the refreshed tokens | INV-02, INV-05 | read the store; refresh at the provider | the refresh fails in transport → `SignedIn` holding the stored tokens (kept) | the bound fires → `SignedOut` | AC-06 — `AuthRepositoryTest::a transport failure keeps the stored session` |
| `Loading` | `restore()` completes with no stored session, local marker set | `localOwnerStore.isLocalSessionActive()` | `Anonymous` | publish `Session.Anonymous(localOwnerId)` | INV-07 | read the local owner id | the id cannot be read → `SignedOut` | the bound fires → `SignedOut` | AC-11 — `AuthRepositoryTest::the local session contacts nothing` |
| `Loading` | `restore()` completes with nothing stored | neither a record nor the local marker | `SignedOut` | publish `Session.SignedOut` | INV-01 | read the store | n/a | the bound fires → `SignedOut` | AC-12 — `AuthRepositoryTest::a failed restore is reported once and ends signed out` |
| `Loading` | `restore()` fails or exceeds its bound | the store could not be read, or did not answer | `SignedOut` | publish `Session.SignedOut`; report one failure | INV-01 | read the store | the store raised or hung → the bound ends the wait | the bound is the outermost bound | AC-12 — `AuthRepositoryTest::a failed restore is reported once and ends signed out` |
| `SignedOut` | `signIn()` succeeds and the session is stored | the provider issued a session and the store accepted it | `SignedIn` | persist the session, then publish it | INV-01, INV-02, INV-09 | provider sign-in; one store write | the store write fails → `SignedOut`; the provider session is ended best-effort; the attempt reports the local cause | cancelled during the write → nothing stored, nothing published | AC-04, AC-07 — `SecureSessionManagerTest::a failed write leaves no session behind`, `AuthRepositoryTest::a store failure ends the session at the provider` |
| `SignedOut` | `signIn()` fails | the provider refused, or the network failed | `SignedOut` | none | — | provider sign-in | reported; the message does not reveal whether the address exists | n/a — no state mutation to roll back | REQ-UA-001 — `AuthRepositoryTest::…` |
| `SignedOut` | `sendMagicLink()` | the address has no account | `SignedOut` | none | INV-01 | one provider call | the provider reports the refusal; **no account is created** | n/a — no state mutation | AC-08 — `SupabaseAuthGatewayTest::a magic link to an unknown address creates no account` |
| `SignedOut` | `verifyMagicLink(email, token)` | the token is valid | `SignedIn` | persist the session, then publish it | INV-01, INV-02 | one provider call; one store write | an invalid token → `SignedOut`, reported as unauthorised | cancelled during the write → nothing stored, nothing published | AC-09 — `SupabaseAuthGatewayTest::a magic link token is exchanged for a session` |
| `SignedOut` | `signUp()` succeeds but yields no session | the address needs confirmation | `SignedOut` | publish `Session.SignedOut` | INV-01 | provider sign-up | reported as "check your mail", not as a failure | n/a | AC-10 — `AuthViewModelTest::a sign up awaiting email confirmation is not reported as a failure` |
| `SignedOut` | `startLocalSession()` | none | `Anonymous` | write the local marker, then publish | INV-07 | one preferences write | the marker write fails → `SignedOut` | cancelled before the marker is written → `SignedOut` | AC-11 |
| `SignedIn` | `refreshOutcome(rejected)` | the provider refused the refresh token | `SignedOut` | clear the store; publish `Session.SignedOut` | INV-06 | provider refresh; one store delete | the delete fails → the session is still cleared in memory; the leftover is erased on the next launch | n/a — no suspension between the mutation and the effect that matters | AC-05 — `AuthRepositoryTest::a rejected refresh token signs the user out` |
| `SignedIn` | `refreshOutcome(transportFailure)` | the device could not reach the provider | `SignedIn` | keep the stored tokens | INV-06 | provider refresh | reported; the session is kept | n/a | AC-06 — `AuthRepositoryTest::a transport failure keeps the stored session` |
| `SignedIn` | `providerSessionChanged(session)` | the SDK refreshed the live session | `SignedIn` | publish the new tokens; the identity is unchanged | INV-05 | provider refresh; one store write | a failed store write leaves the previous record; the error is reported | the previous record stays | AC-13 — `ReadToolsProfileAwareTest::…` |
| `SignedIn` | `signOut()` | none | `SignedOut` | clear the store; clear the local marker; publish `SignedOut` | INV-08, INV-09 | provider sign-out, best-effort and **after** the local clear; one store delete | the provider call fails → `SignedOut` anyway | n/a | REQ-UA-021 — `SignOutKeepsLocalDataTest::…` |
| `Anonymous` | `signIn()` and the ownership transfer | the user chose to move the local data | `SignedIn` | persist the session, publish it, then transfer the local rows | INV-08 | provider sign-in; one store write; the transfer | the transfer fails → `Anonymous` again, with the failure reported | the session is stored; the transfer is not retried | REQ-UA-022 — `LocalAccountClaimerTest::…` |
| `Anonymous` | `signOut()` | none | `SignedOut` | clear the local marker; the owner id stays | INV-08 | one preferences write | the marker write fails → `Anonymous` | n/a | REQ-UA-021 |

### The credential-store machine

| Source | Event / trigger | Guard | Target | State mutation | Invariants checked | Side effects | Failure behaviour | Cancellation behaviour | Verification reference |
|---|---|---|---|---|---|---|---|---|---|
| `Empty` | `storeLoad()` | the current representation holds a record | `Present(session)` | none — the record is read | INV-02 | one keychain read | the read raises → the caller sees no session; the restore's bound applies | the bound fires → the caller sees no session | AC-01 — `SecureSessionManagerTest::a sign in persists the session in the secure store` |
| `Empty` | `storeLoad()` | the current representation is empty; the previous one holds a refresh token and an owner id | `Present(session)` | write the record into the current representation, then erase the previous one | INV-03, INV-04 | one keychain write, then up to four keychain deletes | the write fails → stay `Empty`; the previous representation is left intact and is read again next launch | cancelled after the write and before the erase → `Present(session)` with the previous representation still present; the next launch erases it and does not restore it | AC-02 — `SecureSessionManagerTest::a session in the previous store layout is migrated and remains usable` |
| `Empty` | `storeLoad()` | the current representation is empty; the previous one holds tokens but no owner id | `Present(tokenOnly)` | write the tokens into the current representation, then erase the previous one | INV-03 | one keychain write, then up to four keychain deletes | the write fails → stay `Empty`; the previous representation is left intact | cancelled after the write → the tokens are safe in the current representation; the leftovers are erased next launch | AC-02 |
| `Empty` | `storeLoad()` | the current representation is empty; preferences hold a plain-text token | `Present(tokenOnly)` | write the tokens into the current representation, then erase the plain-text copy | INV-01, INV-03 | one keychain write, then one preferences delete | the write fails → stay `Empty`; the plain-text copy stays and is read again next launch | cancelled after the write → the plain-text copy is erased next launch; the tokens are already safe | AC-03 — `SecureSessionManagerTest::a plain text token is moved into the secure store and erased` |
| `Empty` | `storeSave(session)` | none | `Present(session)` | one keychain write | INV-02 | one keychain write | the write fails → stay `Empty`; the error is reported as `auth.session_not_stored` | the write is a single value, so a cancellation leaves `Empty` | AC-04 — `SecureSessionManagerTest::a failed write leaves no session behind` |
| `Present(session)` | `storeSave(session)` | none | `Present(session)` | one keychain write, replacing the record | INV-02 | one keychain write | the write fails → the previous record stays; the error is reported | the previous record stays | AC-01 |
| `Present(tokenOnly)` | `storeSave(session)` | none | `Present(session)` | one keychain write, replacing the record | INV-02 | one keychain write | the write fails → the previous record stays; the error is reported | the previous record stays | AC-01 |
| `Present(session)` | `storeLoad()` | none | `Present(session)` | none | INV-04 | one keychain read | the read raises → the caller sees no session; the restore's bound applies | the bound fires → the caller sees no session | AC-01 |
| `Present(tokenOnly)` | `storeLoad()` | none | `Present(tokenOnly)` | none | INV-04 | one keychain read | the read raises → the caller sees no session | the bound fires → the caller sees no session | AC-03 |
| `Present(session)` | `storeDelete()` | none | `Empty` | one keychain delete | INV-08 | one keychain delete | the delete fails → the record stays; the sign-out is local regardless | n/a | REQ-UA-021 — `SignOutKeepsLocalDataTest::…` |
| `Present(tokenOnly)` | `storeDelete()` | none | `Empty` | one keychain delete | INV-08 | one keychain delete | the delete fails → the record stays | n/a | REQ-UA-021 |

_Column meanings:_

- **Guard** — the condition under which the transition is permitted. `none` if unconditional.
- **Invariants checked** — the `INV-NN` from `spec.md` that must hold after this transition.
- **Cancellation behaviour** — what happens if the coroutine is cancelled mid-step. "n/a" only for
  steps with no suspension point.

### Transitions the code can take that this table does not describe

- **The provider's client is rebuilt** when the configured project changes
  (`core/auth/SupabaseClientProvider.kt:83-89`). The new client starts with no live session,
  while the store still holds the record. The session machine is not in a state for "the
  client changed", so the table does not describe it; the effect is that the next
  `providerSessionChanged` republishes the identity. **This is unchanged by this change**
  and is listed so that its absence is deliberate.
- **`updatePassword`** changes no session state: it fails, and the session is untouched. It
  is an event with no transition, and is listed here rather than given a row.
- **A provider-side session change that arrives while the repository is between `Loading`
  and its restore result** would publish a session the restore then overwrites. Whether
  this is reachable is **not determined** — it depends on the provider SDK's ordering,
  which this change does not fix and the current code does not guard. It is carried into
  `verification.md` as a scenario to design, not as a modelled transition.

## Side effects

| Side effect | Raised by | Goes to |
|---|---|---|
| Provider sign-in / sign-up / magic-link exchange | the session machine | the provider |
| Provider refresh | the session machine | the provider |
| Provider sign-out | the session machine | the provider |
| Keychain read | the store machine | the platform credential store |
| Keychain write | the store machine | the platform credential store |
| Keychain delete | the store machine | the platform credential store |
| Preferences read / write / delete | both machines | the plain DataStore |
| Local owner id creation | the session machine | the plain DataStore |
| Ownership transfer | the session machine | the database |

None of these is part of the state. The store's writes are the only ones whose failure can
leave a *partially applied* state, and the single-value representation is what removes that
possibility.

## Failure transitions

| The side effect that fails | The state stays | Recoverable | The caller observes |
|---|---|---|---|
| The store write during a sign-in | `Empty`, or the previous record | Yes — sign in again | A failed attempt naming the local cause; the provider session is ended best-effort |
| The store read during the restore | Unknown to the caller | Yes | A signed-out session after the bound, and one failure report |
| The store read never answers | Unknown to the caller | Yes | A signed-out session after the bound, and one failure report |
| The migration's write | `Empty`; the legacy source is untouched | Yes — the next launch retries | A signed-out session |
| The migration's erase | The new record is present; the legacy source still is | Yes — the next launch erases it | A signed-in session; the leftover never replaces it |
| The provider refresh, rejected | `SignedOut` | No | A signed-out session |
| The provider refresh, transport | `SignedIn` | Yes | A signed-in session holding the stored tokens |
| The provider sign-in | `SignedOut` | Yes | A failed attempt with a network cause |
| The provider sign-out | `SignedOut` | Yes | A signed-out session; the failure is logged, not reported over the local clear |
| The local marker write | `SignedOut` | Yes | A failed attempt |
| The ownership transfer | `Anonymous` | Yes | The failure is reported and the device returns to the offline session |

**The one failure this change alters:** a rejected refresh token is a *rejection*, not a
transport failure. Today the two are indistinguishable in the production gateway
(`core/auth/SupabaseAuthGateway.kt:123-125`, `:135-140`), so the rejection is handled as
the transport failure and the session is kept. After this change the rejection is its own
outcome, and the session ends.

## Cancellation and shutdown

- **The restore's bound is the outermost bound.** It covers the store read, the migration
  the read may trigger, and the refresh. Nothing inside it may extend it.
- **The session is persisted before it is published**, and the order is preserved: a
  collector that reacts to the new session by reading a token must find the token already
  there (`core/auth/AuthRepository.kt:545-563`). A cancellation between the two leaves the
  store holding the session and the published session unchanged — the next launch finds
  the session, which is the safe direction.
- **The migration's write is not rolled back.** Cancelling between the write and the erase
  leaves the record in the current representation and the legacy source still present. The
  next launch erases the legacy source and does not restore it. This is the intended
  ordering — write first, erase second — and the crash window it leaves is the one the
  contract asks for.
- **The provider SDK's own session load is a separate lifetime.** The SDK's auth plugin
  reads the persisted session when the plugin initialises, which happens while the client
  is being built (`core/auth/SupabaseClientProvider.kt:101-110`), and the client is built
  on first use by whoever asks for it. **That is not reliably inside the restore's bound**:
  the sync engine and the backup service can build the client first, and their call is not
  bounded by the restore. So the design must not rely on the migration happening inside the
  restore. The constraint this places on `design.md` is stated in
  `## Concurrency and serialization`.
- **The repository's scope is injected**; the store's writes are called by the SDK from the
  SDK's scope. A cancelled SDK write leaves the previous record, because the record is one
  value.

## Concurrency and serialization

- **The store's writes are serialized.** A save racing a delete must not resurrect a
  deleted session: the expected outcome is that the delete wins, because a sign-out that
  leaves a live record behind is a session the user cannot end. The old store serialised
  its one-shot migration with a `Mutex` and an `attempted` flag
  (`core/auth/SecureSessionStore.kt:52-53`); the new store must serialize its writes and
  its migration the same way.
- **The migration runs at most once per process.** Two concurrent first reads must not
  both migrate: one performs the move, the other observes the result. The expected outcome
  is one move, not two, and not a race between an erase and a read.
- **The provider's client is built under a `Mutex`** (`core/auth/SupabaseClientProvider.kt:67-74`),
  so exactly one client exists and the session lives in exactly one place. This is why the
  provider backend must wrap *that* client and not create a second one.
- **The migration must be inside the restore's bound, regardless of who triggers it.** This
  is the constraint the model places on `design.md`: the persisted session must not be
  read as a side effect of building the client, because the client may be built by a
  caller that is not the restore and is not bounded. The design's answer is to load the
  session explicitly, inside the restore's bound, and to keep the plugin from loading it
  on its own.
- **Read-modify-write windows:** the migration is a read, then a write, then erases. It is
  the only read-modify-write in the store, and it is guarded by the one-shot flag.

## Recovery and retry

- **The migration is idempotent.** A repeated launch after a successful migration finds
  the record in the current representation and does nothing; the legacy source is absent,
  or present and erased.
- **A failed migration is retried by the next launch**, because the failed write leaves
  the legacy source untouched. A failed *erase* leaves the legacy source present next to
  the new record; the next launch finds the new record first and erases the legacy source
  without importing it.
- **A failed store write is not retried automatically.** It is reported as a failed
  attempt, and the user signs in again — the provider has already issued a session, so a
  blind retry would be a second sign-in rather than a repair.
- **Terminal states:** `SignedOut` is terminal in the sense that nothing follows from it
  without a user action, and it has a path back — the login screen offers a sign-in and
  the offline session. `Present(tokenOnly)` is terminal for the session machine: the
  restore finds no owner id, so the session is signed out, and only a successful sign-in
  replaces the record. There is no state from which the application cannot recover.
- **No event is lost.** The restore's outcome is published on a channel that is closed
  after the restore, so a reader arriving late still learns what happened
  (`core/auth/AuthRepository.kt:64-80`).

## Invariants

| Id | Restated as a state assertion | Checked by |
|---|---|---|
| INV-01 | No state of either machine holds a token in plain-text preferences; a plain-text token is moved into the store and erased before the machine leaves `Plaintext`. | store rows `Empty --storeLoad()--> Present(tokenOnly)`; AC-03 |
| INV-02 | The store holds at most one record, and a record written as a session is complete — a write that fails leaves no record. | store rows `Empty --storeSave()--> Present(session)` and `Present(session) --storeSave()--> Present(session)`; AC-01, AC-04 |
| INV-03 | A migration never leaves a session in neither place: the write precedes the erase. | store rows `Empty --storeLoad()--> Present(session)` and `Empty --storeLoad()--> Present(tokenOnly)`; AC-02, AC-03 |
| INV-04 | A migration never replaces a newer record with an older one: the current representation wins over every legacy source. | store rows `Present(session) --storeLoad()--> Present(session)` and `Present(tokenOnly) --storeLoad()--> Present(tokenOnly)`; AC-01, AC-02 |
| INV-05 | No state of the session machine holds a vendor type; the published session carries only project types. | session rows `Loading --restore()--> SignedIn` and `SignedIn --providerSessionChanged()--> SignedIn`; AC-13 |
| INV-06 | A rejected refresh ends the session; a transport failure does not. The two outcomes are distinct states, not one. | session rows `SignedIn --refreshOutcome(rejected)--> SignedOut` and `SignedIn --refreshOutcome(transportFailure)--> SignedIn`; AC-05, AC-06 |
| INV-07 | No transition out of, or into, `Anonymous` performs a provider call. | session rows `SignedOut --startLocalSession()--> Anonymous` and `Loading --restore()--> Anonymous`; AC-11 |
| INV-08 | Signing out never removes the local owner id, and never touches the outbox. | session rows `SignedIn --signOut()--> SignedOut` and `Anonymous --signOut()--> SignedOut`; store row `Present(session) --storeDelete()--> Empty` |
| INV-09 | A failed store write leaves no provider-side session alive. | session row `SignedOut --signIn()--> SignedIn` (failure branch); AC-07 |

## Counterexamples

### C-1 — Re-entry: the migration runs twice
- **Sequence:** two callers read the store for the first time at the same moment, both
  finding it empty and both finding the previous representation populated.
- **Expected behavior:** exactly one migration happens. The second caller observes the
  result and does nothing. The previous representation is erased once.
- **Why this is a risk:** two migrations would both write and both erase. If one writes
  and the other reads the *previous* representation before the first write lands, the
  second could import an older record over a newer one — the failure INV-04 forbids.

### C-2 — Parallel calls: a save races a delete
- **Sequence:** a sign-out deletes the record while a token refresh writes a new one.
- **Expected behavior:** the delete wins. The session is not resurrected — a record that
  reappears after a sign-out is a session the user asked to end and cannot.
- **Why this is a risk:** an unlocked write ordering would let the refresh's write land
  after the delete, leaving a live record for a session the user signed out of.

### C-3 — Cancellation mid-flight: the process ends between the migration's write and its erase
- **Sequence:** the migration writes the record into the current representation, and the
  process ends before the legacy source is erased.
- **Expected behavior:** the next launch finds the record in the current representation and
  uses it. The leftover in the legacy source is erased and is never imported.
- **Why this is a risk:** the opposite ordering — erase first, write second — would leave
  the session in neither place, which is exactly the failure INV-03 forbids.

### C-4 — Restart: the same launch is replayed
- **Sequence:** the application launches, migrates, and is killed and relaunched.
- **Expected behavior:** the second launch finds the record in the current representation
  and does nothing. The migration is idempotent.
- **Why this is a risk:** a migration that is not idempotent would re-import the legacy
  source on every launch, and a legacy refresh token that was rotated on the way out is
  usually already revoked — the sign-in would work once and then fail.

### C-5 — The refresh rejection arrives as a transport failure
- **Sequence:** the provider rejects the refresh token; the gateway reports it in the same
  shape as a network error.
- **Expected behavior:** the session ends. The user is signed out and can sign in again.
- **Why this is a risk:** this is not hypothetical — it is what the production gateway does
  today (`core/auth/SupabaseAuthGateway.kt:123-125`, `:135-140`). The user stays signed in
  with a dead token, and every later request is answered with an unauthorised error that
  nothing clears. This change is what makes the rejection its own outcome.

## Mapping to implementation

| Transition | Implemented at |
|---|---|
| `Loading --restore()--> SignedIn` | `core/auth/AuthRepository.kt:366-397` (`doRestoreSession`) |
| `Loading --restore()--> Anonymous` | `core/auth/AuthRepository.kt:405-422` (`restoreLocalSessionOrSignOut`) |
| `Loading --restore()--> SignedOut` (failure or bound) | `core/auth/AuthRepository.kt:303-336` (`restoreSession`) |
| `SignedOut --signIn()--> SignedIn` | `core/auth/AuthRepository.kt:431-436` (`signIn`) → `:499-526` (`attempt`) → `:551-564` (`apply`) |
| `SignedOut --signUp()--> SignedOut` (no session) | `core/auth/AuthRepository.kt:424-429` → `:512-513` |
| `SignedOut --sendMagicLink()--> SignedOut` | `core/auth/AuthRepository.kt:608-621` → the seam's OTP call, which sets `createUser = false` |
| `SignedOut --verifyMagicLink()--> SignedIn` | `core/auth/AuthRepository.kt:623-635` → `apply` |
| `SignedOut --startLocalSession()--> Anonymous` | `core/auth/AuthRepository.kt:460-473` |
| `SignedIn --refreshOutcome(rejected)--> SignedOut` | **not yet implemented** — the production gateway cannot produce this outcome (`core/auth/SupabaseAuthGateway.kt:115-126`). This change introduces it |
| `SignedIn --refreshOutcome(transportFailure)--> SignedIn` | `core/auth/AuthRepository.kt:388-396` |
| `SignedIn --providerSessionChanged()--> SignedIn` | **not yet implemented** — the repository assigns the session by hand today (`core/auth/AuthRepository.kt:561-562`). This change introduces it |
| `SignedIn --signOut()--> SignedOut` | `core/auth/AuthRepository.kt:576-592` |
| `Anonymous --signIn()--> SignedIn` (with transfer) | `feature/auth/LocalAccountClaimer.kt`; the transfer's failure path is `core/auth/AuthRepository.kt` — unchanged by this change |
| store rows (all) | **not yet implemented** — the new store replaces `core/auth/SecureSessionStore.kt:42-232` |

## Verification coverage

| Transition | Scenario | Acceptance |
|---|---|---|
| `Loading --restore()--> SignedIn` | V-01 | AC-06 |
| `Loading --restore()--> Anonymous` | V-02 | AC-11 |
| `Loading --restore()--> SignedOut` | V-03 | AC-12 |
| `SignedOut --signIn()--> SignedIn` | V-04 | AC-01, AC-04, AC-07 |
| `SignedOut --sendMagicLink()--> SignedOut` | V-19 | AC-08 |
| `SignedOut --verifyMagicLink()--> SignedIn` | V-20 | AC-09 |
| `SignedOut --signUp()--> SignedOut` | V-05 | AC-10 |
| `SignedOut --startLocalSession()--> Anonymous` | V-06 | AC-11 |
| `SignedIn --refreshOutcome(rejected)--> SignedOut` | V-07 | AC-05 |
| `SignedIn --refreshOutcome(transportFailure)--> SignedIn` | V-08 | AC-06 |
| `SignedIn --providerSessionChanged()--> SignedIn` | V-09 | AC-13 |
| `SignedIn --signOut()--> SignedOut` | V-10 | REQ-UA-021 (no `AC-NN` — see the note below) |
| `Anonymous --signIn()--> SignedIn` | V-11 | REQ-UA-022 (no `AC-NN` — see the note below) |
| `Empty --storeLoad()--> Present(session)` | V-12 | AC-01 |
| `Empty --storeLoad()--> Present(session)` (legacy complete) | V-13 | AC-02 |
| `Empty --storeLoad()--> Present(tokenOnly)` (legacy partial) | V-14 | AC-02 |
| `Empty --storeLoad()--> Present(tokenOnly)` (plaintext) | V-15 | AC-03 |
| `Empty --storeSave()--> Present(session)` | V-16 | AC-04 |
| `Present(session) --storeSave()--> Present(session)` | V-17 | AC-01 |
| `Present(session) --storeDelete()--> Empty` | V-18 | REQ-UA-021 |

**The `V-NN` labels in this table are the model's own placeholders** for the transitions that
need a scenario. `verification.md` (step 4) assigns the authoritative scenario ids and may
number them differently; the binding that matters is the `AC-NN`.

**Transitions with no `AC-NN` — a gap this step cannot close.** The sign-out and
ownership-transfer transitions are carried by the existing `SignOutKeepsLocalDataTest` and
`LocalAccountClaimerTest`, which must stay green under AC-16. They are not among the
sixteen acceptance criteria in `spec.md`, and this step may not edit `spec.md`
(`speckit-engineering-model`, *Forbidden*). **Two options for the reviewer:** accept the
existing regression tests as the binding, or add an acceptance criterion covering the
sign-out's effect on the store. The decision belongs to the `review-spec` gate.

**Transitions with no scenario at all.** The client-rebuild transition and the
`updatePassword` event have no scenario, because neither changes a state this model
describes. The provider-session-change race — a session change arriving between `Loading`
and the restore's result — has no scenario either, because its reachability is **not
determined**. `verification.md` must either design a scenario for it or record it under
`## Known limitations`.

**The architecture guard** (AC-14) and the **backup exclusion** (AC-15) are not
transitions; they are properties the store's transitions must preserve. They are covered by
`VendorSdkConfinementTest` and `BackupRulesTest` respectively.

---
date: 2026-10-04
status: accepted
---

# Sync core: what is left, and why the remaining items are ordered this way

## Context

`2026-10-04` fixed four defects in the sync core (the cursor, the coalescing guard,
the retry policy, the platform driver) and added the tests they needed. A sweep after
that — reading the code rather than grepping it, which is the only way these show up —
produced the list below.

Not everything found is a bug. Some of it is a design decision that is currently
recorded nowhere, and some of it is a bug that only becomes one when a server is
attached. Writing them down in one place, with the reason each is or is not urgent, is
worth more than a fourth round of fixes in a session that is already long.

## Decided and not yet built

### Sync state moves out of DataStore into Room (`S-4`)

The download cursor is one flat key, `sync/last_lsn`, for the whole app. The
requirement is a cursor per `(owner, profile)`; in DataStore that becomes string
concatenation into a key name, and the write is not transactional with the entity
writes it describes. Room is already a dependency and gives a composite primary key,
transactions and migrations.

**Why it is still open rather than urgent:** today there is one profile and one account
per install in practice, so the collision has no user-visible effect. It becomes a
correctness bug the moment a second profile is used on a device that has already
synced the first — and that is also the moment `SyncCoordinator` starts being asked
for correctness under concurrency, so both changes will want testing at once.

### The patch carries a diff, not an empty op list (`S-1` / phase 2.5)

`buildPatch` still sends `ops = emptyList()` and a `shadowChecksum` computed by
`ConflictResolver`. The comment in the code says the snapshot "already rides along" in
the fields that are always present — it does not; there is no snapshot in the protocol
as implemented. So push currently conveys almost no content, and the row checksum is a
fast-reject for a decision that per-field LWW no longer makes.

Recorded in `2026-10-04-per-field-lww-conflict-policy.md`; the implementation is
blocked on nothing and is the last piece of the client side that must exist before a
server is worth connecting.

**HLC is written nowhere.** `SyncColumns.hlc` exists, `Hlc` and `HlcFactory` are
implemented and tested, and `buildPatch` never sets `syncHlc` — the column is always
null and the factory is called by nobody. It becomes the ordering source under per-field
LWW, so this is dead code that the next change turns into load-bearing code.

## Bugs, not yet fixed

### `userId` on the sync transport (`M-3`)

`SyncRepositoryImpl.testConnection` and `SyncApiClient.getEventsSince` both take a
`userId` and are set up to send it. Once Supabase is attached this is not a redundant
parameter, it is an untrusted one: row-level policies already answer "who is signed in",
and a client-supplied identity must never be part of that answer. It has to come out of
the contract before the first call, not after.

**Small, mechanical, and it is a security-shaped item, so it belongs high on the list
despite being trivial.**

### `outboxDao.getPending()` sits outside `push()`'s try/catch (`M-2`)

In the old `SyncRunner` loop an exception from the cycle body killed the coroutine
permanently, so auto-sync stopped until a process restart. The loop is fixed — it
catches and continues — but the *reason* the plan flagged it still stands one level
down: a throw from `getPending()` now escapes `push()` entirely, because the call is
before the `try`. The coordinator turns that into a failed `Success` outcome, which is
recoverable, but the failed `Result` carries no way to tell "the database was
unavailable" from "the server said no", and the status shown to the user is the same in
both cases.

### `SyncBootstrapper` dispatch is untested

`registerHandler` maps a `DocType` to an `EntityApply`, and each handler ends in an
unconditional repository write — the row-level last-write-wins the ADR replaces. There
is no test for the routing itself, and no test for the `protocolVersion` filter. When
per-field LWW lands, the dispatcher is exactly the code that decides which handler a
given field write lands in, and it will be changed by that work.

## Not a problem, recorded so nobody re-investigates

**`ConflictResolver` is dead weight, not a bug.** It is called by `buildPatch` and its
output is carried in the patch, but nothing reads it client-side. It disappears with the
checksum removal, and the arch gate already lists its `MessageDigest` import as
scheduled-to-die in `2026-10-04-commonmain-jvm-api-gate.md`.

**`viewModelScope`, `runBlocking` and `stateIn` in the sync code are clean.** The plan
§17.2 verified this and the conclusion stands: `viewModelScope` appears only in
comments, `runBlocking` only in the deliberately-named startup bridge, and the single
`stateIn` is in a coordinator rather than a ViewModel.

## Ordering

1. `M-3` — remove `userId` from the transport contract. Trivial, and it is the one item
   where doing it late is a security problem rather than a refactor.
2. `S-4` — sync state into Room, with `SyncBootstrapperDispatchTest` alongside it, since
   both are about per-profile correctness and want the same test scaffolding.
3. `S-1` / HLC — the diff-carrying patch. Last of the client-side work, and the point at
   which connecting a server becomes worth the effort.

## Consequences

- Nothing here is a data-loss bug today. Every item is either inert until a server is
  attached (M-3, S-1), latent until a second profile is used (S-4), or a missing test
  for code that the next change will modify (dispatcher, `getPending`).
- The list is short on purpose. A longer one would be a wishlist; these are the items
  found by reading the code after the fixes, with the evidence for each.

## Links

- `docs/decisions/2026-10-04-per-field-lww-conflict-policy.md` — why S-1 exists
- `docs/decisions/2026-10-04-commonmain-jvm-api-gate.md` — the `ConflictResolver` entry
- `docs/decisions/2026-10-04-test-of-a-fake-is-not-a-test.md` — why the coalescing test
  was deleted rather than rewritten
- `openspec/changes/supabase-auth-and-sync/tasks.md` — phases 2.4, 2.5, 2.7, 2.8

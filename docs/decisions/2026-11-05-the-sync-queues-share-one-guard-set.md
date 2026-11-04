---
title: The sync queues share one guard set
date: 2026-11-05
status: accepted
tags: [sync, database, ownership, fakes]
---

# The sync queues share one guard set

## Context

The outbox, the shadow and the dead-letter shelf are three tables of the sync layer,
and they answer the same question in three different ways: whose work is this, and who
may read it, count it or take it away.

- `sync_outbox` is the reference. It carries `owner_id` and `profile_id`; `getPending`
  filters on both; `deleteByEntity` is owner-and-profile scoped; `deleteForOwner` and
  `countForOwner` scope an erase to one account; `delete` and `clearAll` are
  patch-id-keyed and global. The outbox holds several accounts' work at once on
  purpose — REQ-UA-006 keeps work across a sign-out — which is why an unscoped read
  is a design and not an oversight.
- `sync_shadow` is keyed by `(owner_id, profile_id, entity_type, entity_id)` and every
  one of its queries carries the owner and the profile. Its `confirm` and `release` add
  a second guard, on `in_flight_patch_id`, so a response for a superseded patch cannot
  promote that patch's state over a newer queued one.
- `sync_dead_letter` carries `owner_id` and has the owner-scoped erase and count, but
  its reads are global and its row carries no profile at all.

An audit (#1403's follow-up) asked whether the second and the third still wear the
first's guard set, and whether the fakes that stand in for them behave like the
queries they replace. The fakes had already been repaired once — the outbox's fake
had dropped the owner filter from `getPending` for its whole life, and the shared
fakes had dropped the shadow's promotion guard — and one had not: the dead-letter
fake next to the sync-engine tests returned a snapshot captured when `watch()` was
called, so a flow taken before a patch was shelved reported an empty shelf forever.

## Idea

A transport queue has one shape: it holds the work of several accounts, it is read
whole, it is erased per account, and it can be drained whole when there is no owner
left to attribute the work to. The shelf is a transport queue that gave up, and the
shadow is a transport queue's memory. So the guards do not need three designs — they
need one, and each table's part of it.

## Decision

- **The outbox's guard set is the one.** Global reads, owner-scoped erase and count,
  patch-id-keyed deletes, and a wholesale drain no production path calls — on all
  three tables. `SyncDeadLetterDao`'s unscoped `watch`, `getAll` and `watchCount` are
  therefore deliberate, and the DAO says so next to the query.
- **The guards are pinned by one contract, run against all three implementations.**
  The Room DAOs, the shared fakes in the fake database, and the fakes beside the
  sync-engine tests answer the same scenarios the same way. A fake that cannot
  express a guard is a fake that lets a test assert the guard works.
- **A fake's flow is a flow.** The dead-letter fake's `watch` and `watchCount` are
  cold flows over the current shelf, as Room's are — a flow taken before a row was
  shelved reports that row once it is, both when it is re-collected and to a collector
  that was already open on it.
- **The caller is pinned, not just the signature.** The erase path names each sync
  table by its owner-scoped delete, and a test runs that path with two accounts' rows
  on it. A switch that reached for the shelf's `clearAll` fails that test.
- **The shelf's missing profile is a gap in its shape, not in its guards.** It is a
  schema change, and it is filed as its own piece of work rather than smuggled into
  an audit.

## Rationale

The three tables are one subsystem, and a subsystem with one meaning per question is
cheaper to reason about than one with three. The outbox already carries the answer
and the reasons — the owner filter is what makes a queue *addressable* rather than
merely *filtered*, and the shelf's owner-scoped erase is the same requirement seen
from the other side.

The contract is the part that makes the decision enforceable. A guard that exists
only in a SQL query is invisible to every test that does not open a database, and the
fake that replaces it is free to disagree without anyone noticing — which is exactly
what had happened to the promotion guard and to the outbox's owner filter. Running
the same scenarios against all three implementations turns "the fake agrees with the
query" from an assumption into something a test fails on.

The shelf's missing profile is deliberately left alone. It is the one finding that
cannot be fixed by a query, and fixing it means a column, a migration and a restore
path that says which profile a shelved patch belonged to. Doing that inside an audit
would make the diff two things at once, and the second thing is worth its own
decision.

## Consequences

- The shelf's global reads are a documented choice rather than a leftover. A future
  settings screen that shows shelved patches must scope them itself — the DAO hands
  over every account's work, and the shelf is a multi-account table by design.
- The contract test is one abstract class with three implementations, run against all
  three: the Room one is `slow`, because it is a real database file, and the two fake
  ones are `fast`.
- The dead-letter fake's `rows` is a read-only view of its store, not a mutable list
  the test can write behind the flow's back: a test that mutated it would leave
  `watch` describing a shelf that no longer exists. Every existing caller only reads
  it, so the view is the whole of the contract a test gets.
- A fake's guards are the query's guards. Every fake that stands in for the shadow or
  the shelf reproduces the ownership filter it replaces — the composite key for the
  shadow, the owner filter for the shelf's erase and count — and the contract is what
  keeps the two in step. A fake that dropped a condition would let a test assert a
  guard the fake never had.
- The shelf still cannot be restored to the outbox profile-correctly. The
  `delete`-and-requeue path exists and the DAO's KDoc says a removed patch "can be
  put back on the outbox"; until the shelf carries a profile, that put-back has to
  choose a profile rather than know one.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncOutbox.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncShadow.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncDeadLetter.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/SyncDaoGuardContractTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/SyncEngineFakes.kt`
- `docs/decisions/2026-09-27-write-layer-soundness.md`
- #1403, #1495

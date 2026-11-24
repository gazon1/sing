---
title: "A profile wipe queues its tombstones in one batch, and the sync clock is written once"
date: 2026-11-24
status: accepted
tags: [sync, profile, wipe, performance, outbox]
issue: 3149
---

## Context

Removing a profile's content took hundreds of seconds for ten thousand tasks
and did not finish for fifty thousand (issue #3149, evidence in
`docs/architecture/large-restore-results.md`).

The wipe itself was never the slow part. `ProfileContentResetter` calls five
repositories, and each repository deleted its rows with a set-based
`deleteAllForUser` — one statement per table. What was slow was what the
repositories did *after* the delete: each removed row was handed to
`syncRepository.enqueue` one at a time, and that path cost, per row:

* a read of the active scope,
* a coalescing `DELETE` of the row's previous queued patch,
* a shadow read, a patch build and a shadow write,
* an outbox insert,
* and a **write of the sync clock to the DataStore**.

The last item is the dominant cost. `HlcFactory.tick()` persisted the clock
through `DataStoreHlcStore.save`, which is a file write, and a wipe of ten
thousand rows meant ten thousand file writes of the same fact. A row-count
bound of ten thousand is not the problem; ten thousand *file writes* are.

The boundary the issue draws is the important part: the outbox may not be
bypassed for user-owned data. Every removed row still needs its own
tombstone, or the server never learns the row is gone.

A second cost only shows up at fifty thousand rows: **memory**. A patch
carries the entity's whole JSON state, the shadow row carries it a second
time and the outbox row a third. A batch that holds all of them at once needs
three copies of every removed row in a phone's heap, which is a couple of
hundred megabytes. Measured on the emulator, the fifty-thousand-row wipe
died with `OutOfMemoryError` while building the patches — the batch was
fast but it was not bounded.

## Idea

1. **Drop the per-row tombstones** and send one profile-level delete. Cheap,
   and it loses every remote deletion — forbidden, and wrong.
2. **Keep one tombstone per row but write them one by one.** That is the
   current behaviour, and it is the slow thing.
3. **Keep one tombstone per row and write them in one pass**: read the scope
   once, mint the clocks without writing the clock file after every tick, and
   build, queue and release the patches chunk by chunk.

Option 3 is the same work as option 2 with the per-row I/O amortised and the
per-row memory bounded. The per-row *records* survive, which is what the sync
contract is about.

## Decision

**Option 3. The wipe's tombstones are queued as one batch, the sync clock is
written once per batch instead of once per patch, and the batch works in
chunks so that it never holds more than a chunk of patches at a time.**

Three pieces:

**`HlcFactory.batched { }`** — a block in which `tick()` mints as usual but
does not persist; the last clock minted in the block is written once when the
block ends. The clock is written at the *end* of the block, which is before
the patches that carry it are queued, so a crash leaves the clock **ahead** of
the queue rather than behind it: a clock that skipped ahead loses nothing,
whereas a clock that lagged behind could mint a patch older than one the
server already holds. Outside a batch, `tick()` persists exactly as before.

**The clock write is serialised and monotonic.** Deferring the batch's write
past the monitor that guards the last clock creates a window that did not
exist before: the batch holds an *older* clock, and a tick that runs while the
batch is finishing may write a *newer* one first. If the batch's write lands
last, the stored clock moves **backwards** — and a restart then restores a
clock older than one already pushed, which is exactly the ordering the HLC
exists to provide. Two things close that window, and both are needed:

* every write goes through one mutex, so two writes never interleave; and
* a write that is not newer than the clock already written is **dropped**, so
  the stored clock only ever moves forward.

The second layer is what makes the first sufficient: the mutex orders the
writes but does not decide *which* clock is newer, and a write that was minted
earlier can still reach the store later. The store itself refuses to go
backwards as well (`DataStoreHlcStore.save` writes only when the new clock is
newer than the stored one), so the clock cannot move backwards even if two
devices, or two processes, race on the file.

The mutex is held *outside* the monitor that guards the last clock: a writer
waiting for the mutex needs that monitor to read the newest written clock, so
holding the monitor while waiting for the mutex would deadlock.

**Every batch writes its own clock, not only the outermost one.** Two batches
can overlap: the wipe of a profile and a sync that runs beside it both enqueue,
and nothing keeps them apart. A batch that finished first used to leave
without writing, on the grounds that another batch was still open and would
write the clock. But that batch had already queued its patches by then, and its
patches carried clocks that were never written: a crash in between left the
stored clock older than a clock the device had already issued, and the next
patch could then carry a timestamp older than one the server already holds.

So the write belongs to *the batch*, not to the outermost one: every batch
writes before it returns, and the batch that queued the patches is the batch
that persisted their clock. The number of file writes does not grow: a write
that is not newer than the clock already written is dropped, so a nested batch
and its outer batch leave one write behind.

**The batch's clock write is not cancellable.** The batch suppressed the
per-tick writes, so its final write is the only one that carries a clock
minted during the batch — including a clock minted by an unrelated call
that happens to run while the batch is open. A `finally` of a cancelled
coroutine runs in a cancelled context, where a suspending call throws
before it does anything: without an explicit `NonCancellable` the write
would be skipped exactly when the batch was abandoned, and the stored
clock would stay older than a clock the device already issued. The write
is a single local file write, so it cannot hold a cancellation for long.

**`SyncPatchBuilder.forEachChunk`** — mints the whole batch's clocks up front
(one `batched` block, one clock write, before anything is queued), then walks
the batch in pieces of `SQL_BIND_CHUNK` (500) entities: one shadow read, one
shadow write and one outbox insert per piece. The patch is built by the same
code the single-entity path uses (`buildPatch`, extracted from `build` so
both share it): same diff against the same confirmed state, same fields
dropped for the server, one distinct clock each.

**`OutboxService.enqueueAll`** — one scope read, then per chunk: one
coalescing delete and one `insertAll`. The `IN (…)` lists are cut into pieces
of 500 ids (`SqlBindChunk`), because SQLite caps the parameters of a
statement and a wipe has as many ids as it has rows.

The port keeps the name `enqueue` — `SyncRepository.enqueue(entities:
List<SyncableEntity>)` overloads the single-entity one — so the lexical
architecture rules that scan for `syncRepository.enqueue(` still see the
repositories' call sites.

**`ProfileContentResetter` runs the whole reset in one transaction.** The
reset touches many tables, and each repository in it writes through a unit
of work of its own. Without a transaction around the reset, every one of
them commits on its own: a failure in the middle leaves the profile half
deleted — the tags are gone, the tasks are not — and nothing records which
half ran. One transaction around the reset makes it all or nothing; the
repositories' own writes join it rather than opening a transaction of
their own, which is what `UnitOfWork` already promises and
`UnitOfWorkIsAtomicTest` already asserts.

The attachment **files** are the one thing a transaction cannot cover: a
file is not a row, so a rollback cannot bring it back. They are deleted
after the commit instead of before it. A file that outlives its row is
invisible garbage in a profile that no longer exists; a row that outlives
its file is a broken attachment in a profile that is still there.

What did **not** change:

* `ProfileWipePlan` is untouched. The wipe still goes through the same
  five repositories, in the same order, with the same ownership checks —
  only the transaction boundary around them is new.
* Every removed row still gets its own patch. Nothing is summarised, and no
  remote deletion is lost.
* The single-entity `enqueue` path is semantically unchanged.
* `OwnerScopedEraser` (the account switch) is untouched: it was already
  set-based.

## Rationale

**Why the clock write and not the row count is the thing to fix.** The outbox
grows with the wipe — that is the contract, and the issue forbids shrinking
it. But the *statements* that write the outbox are already cheap compared to
the clock: a DataStore write is a file write with its own fsync and its own
serialisation, and it happened once per removed row. Batching the statements
without batching the clock would have made the wipe faster and still left the
dominant cost in place.

**Why the clocks are minted up front rather than inside the chunk loop.** The
clock file has to be written before the patches that carry those clocks are
queued, or a crash leaves the queue ahead of the clock. Minting the whole
batch's clocks in one `batched` block writes the file once, up front, and
keeps that property regardless of whether the caller wraps the batch in a
transaction.

**Why the clock write is serialised *and* monotonic.** The batch's write is
deferred past the point where the last clock is guarded, so it can arrive at
the store after a newer clock has already been written. A mutex alone does
not prevent that: it orders the writes, but the older clock may still be the
one that lands last. Dropping a write that is not newer than the clock
already written is what makes the stored clock move forward only. The store
refuses an older clock as well, so the property survives a race the factory
did not see — two devices writing the same file, or a process that was killed
between the write and the update of the in-memory record.

**Why the batch suppresses the per-tick write globally.** The suppression is
per *factory*, not per batch: a tick minted by an unrelated call while a batch
is running does not write its clock by itself. Nothing is lost — the tick is
either counted in the batch's final write, or it sees that the batch is over
and writes itself — but the property is worth stating, because it is the one
that a reader of `tick()` would otherwise assume is broken.

**Why every batch writes its clock, and not only the outermost one.** The
suppression of the per-tick write is global per factory, so a tick minted
anywhere while a batch is open is carried by that batch's write. If the
batch that queued the patches leaves without writing — because another batch
happened to be open at that moment — those patches are queued with clocks that
exist nowhere but in memory. The batch is the unit that owns the clock of the
patches it queued, so it is the unit that writes. Writing the clock twice for
two overlapping batches costs nothing: the monotonicity rule drops the write
that is not newer than the one already stored.

**Why the batch's write is not cancellable.** Because the suppression is
global, the batch's final write is the *only* write that carries a clock
minted during the batch. If the batch is cancelled before that write lands,
the clock is lost — and the stored clock then says less than the device
already issued. A cancelled coroutine's `finally` runs in a cancelled
context, where a suspending call throws before it does anything, so the
write has to opt out of the cancellation explicitly. The cost is bounded: it
is one local file write.

**Why the reset is one transaction.** The issue asks for a cleanup that is
"atomic or has a documented, tested resumable boundary". A resumable
boundary needs a durable record of what already ran — a new table, a state
machine, a retry contract — which is a second, larger piece of machinery
than the problem needs. One transaction is the smaller answer: the reset
either lands whole or not at all, and there is nothing to resume because
there is no half-done state. The transaction does not grow with the
profile: the deletes are set-based, and the patches, shadows and outbox rows
are still produced and released one chunk at a time, so the transaction
holds a bounded amount of memory. The repositories' own writes join the
outer transaction, which `UnitOfWork` already promises.

**Why the attachment files are deleted after the commit.** A transaction
covers rows; a file is not a row. Deleting the files first would leave the
attachment rows pointing at files that are already gone whenever the
transaction rolls back — a broken attachment in a profile that is still
there. Deleting them after the commit leaves, in the worst case, a file
whose row is gone: invisible garbage in a profile that no longer exists.

**Why the batch is chunked.** The patches are the memory, not the statements.
Fifty thousand rows' worth of patches, shadows and outbox rows do not fit in
a phone's heap — the first version of this change died with an
`OutOfMemoryError` on the emulator at fifty thousand rows. A chunk of five
hundred rows is small enough to build, queue and release before the next one
starts, so the peak memory is set by the chunk, not by the profile.

**Why the chunk size is the bind size.** SQLite caps the parameters of a
statement, so the `IN (…)` lists are cut into pieces of 500 ids anyway.
Building the patches in the same pieces means the ids a chunk reads and the
patches a chunk writes are bounded by the same number.

**Why the batch reads the shadows instead of skipping them.** A patch has to
be diffed against what the server *confirmed*, not against the local state,
or the fields only the earlier patch carried would be lost when that patch is
coalesced away. Reading the shadows per chunk preserves that exactly.

**Why `enqueue` is overloaded rather than renamed.** The architecture gates
that watch the sync write path scan for the literal call `syncRepository
.enqueue(`. A new name (`enqueueAll` on the port) would have moved the
repositories out of the gates' sight, which is exactly the kind of quiet the
gates exist to prevent. The *implementation* is `enqueueAll`; the *port* is
`enqueue`.

**Why there is no bulk-delete pipeline.** The issue forbids one, and there
was no need for one: the deletes were already set-based. The waste was
entirely on the sync side of the delete.

## Consequences

* A wipe of ten thousand rows issues one scope read, one clock write, and per
  chunk of 500 rows one shadow read, one shadow write, one coalescing delete
  and one outbox insert — instead of one of everything per row.
* The patches the batch produces are the patches the per-entity path
  produced: same ops, same base version, same entity type, one clock each.
  `OutboxServiceBatchTest` compares the two paths field by field.
* The statement count is bounded by the number of chunks, not by the number of
  rows. `OutboxServiceBatchTest` asserts it: 1200 entities, three insert
  statements, three delete statements, zero per-entity statements — and no
  statement carrying more than a chunk of rows.
* The clock write is now a `suspend` write at the end of a batch, so the batch
  path waits for it. The single-entity path keeps its fire-and-forget write.
* The stored clock only ever moves forward. A clock written while a batch is
  finishing is not rolled back by the batch's write, and the store refuses an
  older clock of its own accord.
* A cancelled batch still writes its clock. The write is the only one that
  carries a clock minted during the batch, so it is not cancellable.
* Every batch writes its own clock before it returns, so a batch that finishes
  while another batch is still open does not leave its queued patches without
  a clock on disk. `HlcFactoryPersistenceTest` asserts that overlap.
* The reset is atomic: a failure anywhere in it leaves the profile whole
  instead of half deleted. `ProfileResetReachesTheWholeProfileTest` asserts
  that a failing repository undoes the deletions that came before it.
* The attachment files of a wiped profile are deleted after the commit. A
  crash between the commit and the deletion leaves files whose rows are
  gone — invisible garbage, not a broken attachment.
* The outbox grows exactly as before. A device with a large profile still
  holds one queued patch per removed row until they are pushed, and the push
  side already takes them in batches (`PushPhase`).
* The batch's peak memory is set by the chunk, not by the profile — but the
  repositories still hold the removed rows and their tombstones in memory
  while they build the batch, so a profile of hundreds of thousands of rows is
  still out of reach.
* Measured: the cleanup of a profile with ten thousand tasks took 457 s on
  the reference device before the change and 20.3 s after it; on the emulator
  155.8 s became 5.0 s, and the fifty-thousand-row profile, which died with
  `OutOfMemoryError`, is cleaned in 40.4 s.
* **Not re-measured:** those numbers were taken before the reset became one
  transaction. The transaction holds the whole reset open, so the wall clock
  on a device may differ; the memory bound is unchanged, because the
  transaction holds rows that were already in memory, not a new copy of them.
  The 50k measurement on the reference device is still open.

## Links

* Issue #3149 — the issue this answers.
* `docs/architecture/large-restore-results.md` — the measurements, before
  and after.
* `shared/src/jvmTest/.../core/sync/OutboxServiceBatchTest.kt` — the batch
  contract, against the production patch builder.
* `shared/src/jvmTest/.../core/sync/HlcFactoryPersistenceTest.kt` — the clock
  write.
* `shared/src/jvmTest/.../core/sync/HlcStoreTest.kt` — the store's refusal to
  move the clock backwards.
* `shared/src/jvmTest/.../feature/profile/ProfileContentWipeTest.kt` — the
  wipe's real path: the tombstones it queues and the rollback when a chunk of
  them fails.
* `shared/src/jvmTest/.../core/wipe/ProfileResetReachesTheWholeProfileTest.kt`
  — the reset as a whole, including the rollback of a failed reset.
* `shared/src/commonMain/.../core/wipe/ProfileContentResetter.kt` — the entry
  point, and the transaction boundary around the reset.

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

What did **not** change:

* `ProfileContentResetter` and `ProfileWipePlan` are untouched. The wipe still
  goes through the same five repositories, in the same order, inside the same
  transactions, with the same ownership checks.
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

## Links

* Issue #3149 — the issue this answers.
* `docs/architecture/large-restore-results.md` — the measurements, before
  and after.
* `shared/src/jvmTest/.../core/sync/OutboxServiceBatchTest.kt` — the batch
  contract.
* `shared/src/jvmTest/.../core/sync/HlcFactoryPersistenceTest.kt` — the clock
  write.
* `shared/src/commonMain/.../core/wipe/ProfileContentResetter.kt` — the entry
  point, unchanged.

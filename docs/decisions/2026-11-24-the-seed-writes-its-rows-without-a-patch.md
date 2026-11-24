---
title: "The seed writes its rows without a patch, and the bulk write leaves the task repository"
date: 2026-11-24
status: accepted
tags: [testing, maestro, android, seed, sync, performance]
---

# The seed writes its rows without a patch, and the bulk write leaves the task repository

## Context

`SeedWriter.createTasks` wrote a thousand-row Maestro fixture through
`TaskRepository.createAll`, which called `create` per row. Each row cost an upsert,
an outgoing-links write, a fresh-row read, a sync-outbox write, a shadow write and
a JSON patch — measured at ~30 ms a row on a realme RMX3938 (`0O65321F32204DD5`),
E2E build `0.1.0+g90441afd5aec`:

| rows | time  |
|------|-------|
| 100  | ~4 s  |
| 300  | ~10 s |
| 1001 | ~30 s |

The largest wait the Maestro layer has is the shell budget — 30 s
(`config/docs/ui-wait-budgets.txt`), and every other kind is smaller. A seed that
takes 30 s therefore cannot be waited for at all: the helper's shell wait times out
while the seed is still writing, and the flow fails on a wait that has nothing to do
with what the flow is testing.

`Maestro/flows/agenda/07-large-overdue.yaml` wrote its thousand overdue rows in
five chunks of ~200 because one chunk fitted inside the budget. That is a workaround
for this defect, not a design.

The transaction was never the problem: the nested writes join the outer
`unitOfWork.write`, so the seed was already one commit. The cost was the **per-row
sync work**, not the per-row commit.

## Idea

A seeded row and a user's row are different contracts, and the write path should say
which one it is being asked for.

A user's edit has to reach the server, and the server learns about it through the
patch that `enqueueFresh` writes. A seeded row is a fixture: the flow that seeded it
asserts on it on this device, in this run, and a debug build has no server to tell.
So the patch is not an inefficiency to trim from the seed — it is the reason the
ordinary create is expensive, and the seed does not need it at all.

## Decision

**`SeedTaskWriter` is a narrow port beside `TaskRepository`, not a method on it.**
It has exactly one operation — `writeFixtures(tasks)` — and exactly one caller,
`SeedWriter.createTasks`. It writes one statement per row in one transaction,
scoped to the active profile, keeping the tags and follow-up edges the task carries,
and enqueues no patch.

`TaskRepository.writeFixtures` is **removed**. It existed for this one caller, and a
bulk write on the generic contract made "these rows will never be synchronised" the
invisible default of any future caller. A caller that wants rows without patches now
has to ask for `SeedTaskWriter` and accept the name.

`TaskRepositoryImpl` implements both interfaces, and `tasksModule()` binds
`SeedTaskWriter` to the same singleton through `get<TaskRepository>() as SeedTaskWriter`.
A second instance would be a second write path to keep correct.

The batch write reuses the existing machinery and adds no framework: `taskDao.upsert`
with the row mapper that already derives `outgoing_links`, the existing
`upsertTagCrossRefForUser` / `upsertDependencyForUser`, and the existing
`unitOfWork.write`. `BackupImporter`'s direct-DAO batch is the same pattern, for the
same reason — a restore writes rows for an arbitrary user, which the ambient-scoped
repositories cannot express.

Ordinary `create`, `createSeries` and `update` are unchanged: a user's write still
enqueues, inside the same transaction, exactly as before.

## Rationale

The defect was not "the seed is slow". It was that the seed's speed was decided by a
requirement the seed does not have, and the contract hid that. A method named
`writeFixtures` on `TaskRepository` reads as "the same write, batched" — which it was,
per-row sync included. Splitting the port makes the trade-off explicit at the one
place it can be made: the caller that chooses to write fixtures.

Keeping the port inside `core/seed` rather than in `feature/tasks/domain/port` is
deliberate. It is not a task capability; it is a fixture capability that happens to
write task rows. Putting it beside the seeding rules keeps the generic task contract
free of a "do not sync this" mode, which is the sort of mode that eventually gets
picked by the wrong caller.

## Consequences

- `SeedWriter` takes `seedTasks: SeedTaskWriter` as a constructor parameter. Every
  construction site passes it — `DebugSeedActivity`, one androidTest, two commonTest
  classes.
- `FakeTaskRepository` implements `SeedTaskWriter` too, so the seeding tests keep
  running on the fake; its `writeFixtures` writes every row and reports no sync, which is
  all a fake can honestly say.
- A seeded row that a later user action edits goes through the ordinary `update`,
  which enqueues it. The absence of a patch is a property of the seed write, not of
  the row.
- The five-chunk workaround in `07-large-overdue.yaml` is no longer needed.
- **Re-measured after the change**, same device (realme RMX3938, `0O65321F32204DD5`),
  timing from the deep link to the `seeded N tasks` log line:

  | rows | before | after  |
  |------|--------|--------|
  | 100  | ~4 s   | 0.26 s |
  | 300  | ~10 s  | 0.51 s |
  | 1001 | ~30 s  | 0.9–1.1 s (5 runs) |

  The thousand-row seed now fits the 30 s shell budget with room to spare, at ~1 ms a
  row instead of ~30 ms.
- `writeFixtures`'s KDoc claimed "one commit means observers see one change instead of
  one per row". That was true only of the commit; the per-row queries and the outbox
  writes were still per row, and the sentence is the reason nobody measured.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/seed/SeedTaskWriter.kt` —
  the new port.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/seed/SeedWriter.kt` —
  `createTasks`, the only caller.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/data/TaskRepositoryImpl.kt`
  — the implementation, beside `create`.
- `docs/decisions/2026-10-05-who-owns-a-row-and-the-patch-that-describes-it.md` —
  why the ordinary write and its patch share a transaction; the seed opts out of the
  patch, not out of the transaction.
- `config/docs/ui-wait-budgets.txt` — the 30 s shell budget the seed had to fit in.

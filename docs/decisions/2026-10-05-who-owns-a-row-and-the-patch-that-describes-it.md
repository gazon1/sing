---
title: Who owns "the row and the patch that describes it, or neither"
date: 2026-10-05
status: open
---

# Who owns "the row and the patch that describes it, or neither"

## Context

Every repository that writes a synced entity does the same two things: it writes the
row, and then it enqueues a patch describing the change.

```kotlin
// TaskRepositoryImpl.kt:206-215
override suspend fun create(item: Task): Result<Task> = runCatchingCancellable {
    ...
    taskDao.insert(toInsert)
    syncRepository.enqueue(toInsert)   // ← a second store, through a second port
    ...
}
```

Six repositories do this, identically: tasks, notes, projects, tags, tag groups, and the
archive path. There is no `withTransaction` anywhere in `commonMain` — the audit of the
auth/sync test plan found zero occurrences — and the test plan expects the pair to be
atomic (OB-04).

The absence is not a missing call. The two writes go through **two different
abstractions**: the entity DAO, which the repository owns, and `SyncRepository`, which it
holds as a collaborator and which reaches the outbox table inside the same database. So
there is no seam where a transaction could be opened without a repository first being
given something it does not have.

`SyncState.kt:42` already names the gap in passing — the flat DataStore keys "were not
transactional with the entity writes they describe" — so the codebase knows and has not
decided.

## Idea

1. **Give the repositories the database.** Each wraps its two writes in
   `useWriterConnection { }`. One line at each of the six sites plus a constructor
   parameter. The `SyncRepository` port is bypassed inside the transaction, or it is
   called from inside it and takes part in it.
2. **A unit-of-work port.** The repository calls something like
   `unitOfWork.write { dao.insert(…); enqueue(…) }`, and that thing holds the database and
   performs both. One place to get right, six call sites that only change shape.
3. **Move the enqueue into the write that caused it.** The entity DAO becomes the only
   writer, and the outbox row is produced by a Room trigger or by the same `@Transaction`
   DAO method. No second port involved, because there is no second write.

## Decision

**Undecided — and that is the finding.** Option 2 is the shape I would choose, and the
reasons are worth recording so the choice is made rather than drifted into.

**Option 2, over option 1:** the transaction has to be correct in six places, and the six
places are six repositories that will each be edited by a different person for an
unrelated reason. A per-repository transaction is correct only as long as nobody adds a
write to the middle of one. A single unit of work is correct once, and the six call sites
become a shape that cannot be got wrong — there is nowhere to put a second write that is
outside it.

**Option 3 is the one to be careful about.** It is genuinely better — no second port, no
possibility of the pair diverging — and it is a Room-specific mechanism, so the JVM and
Android builds share it and the `commonMain` abstraction does not have to grow at all.
But it moves the outbox from something a repository asks for into something a database
does, and `SyncOutbox` carries patch payloads built by the diff, not by the row. A trigger
cannot build a patch. What it *can* do is record "entity X changed" and leave the patch
construction where it is, which is a different design from the one the current code has
and deserves its own consideration rather than a mention here.

## What the decision costs either way

- **The seed and the outbox coalescing.** A unit of work has to compose with the
  `deleteByEntity`-then-insert coalescing in `SyncEngine.enqueue`, which is itself two
  writes. That pair is inside the transaction, or the coalescing is not atomic, and the
  plan's OB-05 (two rows for one entity) depends on it.
- **The pull side is a separate problem.** A page of fifty events is applied row by row
  with nothing around it (PL-13), and that one is *not* a repository-pair question — it
  is the engine needing a transaction over its own batch. Whatever is chosen for the
  write path has to be reusable for the read path, or the project will own two answers.
- **Nothing forces this soon.** The failure needs a crash between two writes, and the
  window is small. It is worth fixing before it is urgent, not urgently.

## Consequences if option 2 is chosen

- `SyncRepository.enqueue` stops being a port a repository calls and becomes a step
  inside a unit of work, which is a change to the port's meaning rather than to its
  signature. `SyncEngine.enqueue` is the same story: it is the write half of a pair that
  the engine does not own either end of.
- Six repositories gain a dependency and lose the ability to enqueue without it. A test
  that seeds an outbox directly has to construct a unit of work, which is a real cost in
  the six fixtures that do that today.
- The `offline-sync` spec can then state the atomicity as a requirement. Until the
  decision, a requirement about it would be fixing a capability that does not exist.

## Links

- Issue #178 for the version half of the same file's lifecycle, which this does not
  block.
- Test plan §6.1 OB-04 and OB-05, §6.6 PL-13.
- `2026-10-04-sync-state-keyed-by-owner-and-profile.md` — the owner-scoped rows a unit of
  work has to span. (This reference previously named a dated sync-state ADR that resolves to
  no file in `docs/decisions/` or its archive, so the reference was dangling rather than
  merely stale. A 2026-09-23 ADR of a similar name also exists but documents the sync API
  shape rather than owner-scoping, so it is not the right target either.)

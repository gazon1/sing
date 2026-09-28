---
title: "MR-3 retro — the write-layer sweep left two read leaks, and the note split does not clear its baseline"
date: 2026-09-28
tags: [retro, tech-debt, repository, multi-profile, security, detekt]
status: accepted
epic: refactor/tech-debt-roadmap-v3
---

Retro after MR-3 of the tech-debt roadmap (`mr-3-repository-cleanup`).

## Inventory

| Metric | Value |
|---|---|
| Production files changed | 4 (`Daos.kt`, `TagsRepositoryImpl`, `TagGroupRepositoryImpl`, `NotesRepository`) |
| Test/support files | 3 (`FakeAppDatabase`, 2 new isolation suites) |
| New tests | 5, each confirmed to fail before its fix |
| `:shared:jvmTest` | 1140 tests, 0 failed, 0 skipped |
| detekt (shared) | 0 findings |

## CRITICAL — two cross-user read leaks, both live

`2026-09-27-write-layer-soundness` ran eight MRs over the write layer and closed its
ledger with `TaskDao.getById` (ledger #1), the unscoped read that let `get(id)` return
any user's task. Its own text says the MR-6 phase review *"also surfaced a fixture that
only passed because the fake was too permissive"* — but the sweep's frame was **writes**,
and the two remaining unscoped reads were in files it did not touch.

| DAO query | Called from | Leaked |
|---|---|---|
| `SELECT * FROM tags WHERE id = :id` | `TagsRepositoryImpl.observeTag` | a tag of another profile |
| `SELECT * FROM tag_groups WHERE id = :id` | `TagGroupRepositoryImpl.observe` | a tag group of another profile |

The tag query is worse than a plain cross-user read: it also lacks the `deleted_at` filter
its scoped sibling carries, so `observeTag` returned **soft-deleted** tags as well. Both
repositories read no user at all on that path — `observeTag` did not so much as consult
the ambient user.

`TagDao.watchByIdForUser` already existed and was unused by the repository.
`TagGroupDao` had no scoped `watch` at all, so `watchByIdForUser` was added next to the
existing `getByIdForUser`. No schema change. `FakeTagGroupDao` implements the new method
with the same ownership predicate as production.

Five tests, each verified to fail against the pre-fix implementation before being kept:
another profile's tag invisible, soft-deleted tag invisible, own tag visible; same pair
for tag groups.

**Rule:** a sweep scoped to *writes* cannot find *read* leaks, and a ledger that closes
after a write sweep is not evidence the read side is clean. Sweep both directions, or say
which one was covered.

## Follow-up worth doing first — a Konsist rule for reads

`ArchitectureTest` has `DAO mutations are ownership-scoped`. There is no counterpart for
reads, which is the mechanical reason these two survived eight MRs of write-layer work.
A read rule has the same shape as the write one already in place: a `@Query` whose SQL
mentions `SELECT` and references an entity table must filter on `user_id` (or on a
subquery that does), with an allowlist for the deliberate exceptions the write rule
already carries — `profiles` (the row id *is* the profile id), `llm_usage` (a per-event
log), `calendar_sync_task_map` (ledger #17), and the cross-ref `@Upsert`s kept for
`BackupImporter`.

That is the change that stops this class recurring. It is not done here because the
exception set has to be enumerated and verified against the real queries first, and a rule
written from a guess would be a rule that cries wolf.

## The note split does not do what the roadmap said

MR-3 was scoped to split `NotesRepository.kt` (544 lines) because detekt reports
`TooManyFunctions` on it. It does — and both entries are in the baseline, which is why
detekt prints 0. Moving the mappers to `NotesMappers.kt` is a real structural improvement
and is kept. **It does not clear the baseline**, because the limits that fire are not the
ones the split addresses:

```
Interface 'NotesRepository' with '20' functions detected. The maximum allowed functions
per interface is set to '11'.
Class 'RoomNotesRepository' with '28' functions detected. The maximum allowed functions
per class is set to '11'.
```

`allowedFunctionsPerFile: 25` in `detekt.yml` governs the file-level rule; the interface
and class variants carry their own limit of 11, which the file does not contain. Getting
either declaration under 11 is an API redesign — the interface's 20 methods are its public
contract. The baseline entries stay, and the file is recorded here instead.

The plan also asked to rename `RoomNotesRepository` → `NotesRepositoryImpl` to match the
`*RepositoryImpl` convention. **Not done, deliberately:** `RoomSavedAgendaViewsRepository`,
`RoomReminderRepository` and `RoomChecklistRepository` deviate the same way. Renaming one
makes the codebase less consistent, not more. All four is a mechanical MR.

## Scoped out

- **`AttachmentRepository.create()` has no callers** — not in production, not in tests.
  `saveFileAttachment` and `addUrlAttachment` build their entity from the ambient user, so
  they are safe by construction. The plan's "add `assertCanWrite`" would have guarded a
  method nobody calls. Left for a decision on whether to delete it or keep it for a
  planned bulk-import path, which is how `BackupImporter` reaches the unscoped
  `@Upsert` cross-ref methods.
- **Making `TagGroupRepository` / `ReminderRepository` / `AttachmentRepository` extend
  `GenericUserScopedRepository`** is not a one-line change. The base requires
  `create(item: E): Result<E>`, `update(item: E): Result<E>` and `restore(id)`;
  `TagGroupRepository` has `create(input: CreateTagGroupInput)`,
  `update(input: UpdateTagGroupInput)`, no `restore`, and an `upsert(tagGroup): TagGroup`
  returning a bare entity — the inverse of the write-layer contract. Adopting the base is a
  contract change with callers, not a cleanup.
- `UpdateProjectUseCase` returns `Result<Unit>` where `UpdateTaskUseCase` returns
  `Result<Task>`; its full-entity overload has no callers and is not deprecated. Carried
  from the MR-2 retro.

## Rules

- **Verify a claim about callers before writing a guard.** `create()` looked like a
  missing-`assertCanWrite` hole in the audit; it was a method with no callers.
- **Read the rule's own limit before assuming a refactor clears it.** The baseline entry
  said `TooManyFunctions`; the message says *per interface*, limit 11. Those are different
  problems and a file split only addresses one.
- **A write-layer sweep is not a read-layer sweep.** Say which one ran.

## Links

- `2026-09-27-write-layer-soundness` — ledger #1, the TaskDao read leak; the sweep whose
  frame left these two behind
- `2026-09-26-konsist-architecture-tests` — the DAO-boundary Konsist rules. The write-side
  rule is `DAO mutations are ownership-scoped`; there is no read-side counterpart, which is
  why this class of defect was never gated
- `2026-09-28-mr2-project-detail-retro` — the preceding MR

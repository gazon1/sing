---
title: "Write-layer soundness — ownership-scoped DAO mutations and the two-layer guard model"
date: 2026-09-27
tags: [repository, multi-profile, sync, architecture, security]
status: accepted
---

## Context

The write pipeline documented in `GenericUserScopedRepository` KDoc — `assertCanWrite` →
`dao.upsert` → `syncRepository.enqueue` — was documented but **not enforced**, and reality
diverged in three independent ways. A full sweep of `shared/src/commonMain` found:

| Class | Count | Severity |
|---|---|---|
| DAO mutations with no `user_id` in the WHERE clause | 28 | cross-user write |
| Syncable entities with bypassed `enqueue` | ~25 | deletions never propagate |
| Repository writes with no guard of any kind | 36 | — |
| Fakes divergent from production | 5 fakes, ~14 methods | tests blind to isolation |

The fakes were the most consequential finding. `FakeProjectsRepository.observeProject`,
`observeByParent`, `changes` and `findByIdempotencyKey` did not filter by `userId` at all,
`FakeTaskRepository.delete` hard-deleted where production soft-deletes, and
`FakeNotesRepository.search` searched body text where production searches title only. Any
test asserting cross-user isolation passed **vacuously** — which is why the defects
survived: nothing in the suite could see them.

Two structural facts shaped the fix:

1. **`assertCanWrite` needs an entity.** `toggleComplete(taskId)`, `softDelete(id)` and
   `setTags(taskId, tags)` carry only an ID — there is no `userId` to check. Adding a
   guard there would be impossible or a lie. The unbypassable layer is the DAO.
2. **Room constrains INSERT returns.** `INSERT` queries may return only `Unit` or `Long`,
   and a rejected `INSERT ... SELECT` reports `-1`, not a row count. Enforcement for
   cross-ref tables is therefore the `WHERE EXISTS` clause, with callers checking task
   ownership separately.

## Decision

### 1. Two-layer guard model

- **Entity-carrying writes** (`create`/`update`) → `currentUser.assertCanWrite(...)`, which
  fails fast with `CrossUserWriteException` and is now applied uniformly.
- **ID-only writes** (`toggleComplete`, `softDelete`, `setTags`, …) → the DAO filter plus
  `require(rows > 0)` at the call site. This is the layer that cannot be bypassed.

`assertCanWrite` is explicitly *not* universal, and the `singularity-todo-write-pipeline`
skill is updated to say so.

### 2. Ownership-scoped DAO mutations

Every `UPDATE`/`DELETE` takes a `userId` and reports the affected row count. Tables without
a `user_id` column (`task_tags`, `task_dependencies`, `checklist_items`,
`project_tag_groups`) scope through their owning row, following the pattern already used by
`listAllTagsForUser`:

```sql
WHERE task_id IN (SELECT id FROM tasks WHERE user_id = :userId)
```

Old unscoped variants were **deleted**, not left alongside, so the mistake cannot be
repeated. The only exceptions are the two unscoped `@Upsert` cross-ref methods kept for
`BackupImporter` (see ledger), and two deliberately global queries.

**No Room migration** — no columns changed, only query signatures.

### 3. Deletion propagates as state, not as a tombstone

`softDelete`/`restore` never called `enqueue`, and `SyncEngine.buildPatch` hardcodes
`isDelete = false`, so **deletions never reached the server** and the next pull re-applied
the server's non-trashed state — tasks resurrected. `deltaPatchDelete` exists but its only
caller is a test.

`buildPatch` already ships the full entity snapshot (`ops = emptyList()`, `shadowChecksum`),
so the fix is to enqueue the *rebuilt* entity. `archivedAt`/`isDeleted` are serializable
fields, so this needs **no server-side change**, and `restore` works through the same path.
`isDelete` stays `false`; a comment on `buildPatch` records why.

### 4. `Result.success` must not lie

`toggleComplete`/`togglePinned` previously did `?: return@runCatching` on a missing entity,
so a caller could not distinguish "not found" from "toggled". Missing rows now surface as
`Result.failure`.

## Consequences

- **Always** — DAO mutations carry `userId` and return the affected count; a `0` is a
  failed write.
- **Never** — leave an unscoped DAO mutation next to a scoped one; delete the old variant.
- **Never** — treat `assertCanWrite` as the sole ownership check for id-only methods.
- A Konsist rule and a detekt rule (added in the enforcement MR) fail the build on new
  violations, with an allowlist for the intentional exceptions.
- Fakes must reproduce production semantics — including ownership. A fake that cannot
  evaluate a rule must say so in a comment rather than silently diverge.

## Phase reviews

Each MR closes with a re-sweep of the four bug classes plus a regression check.

| After | Result |
|---|---|
| MR-1 (Task/Note DAOs) | TaskDao and NoteDao fully scoped. Found: unscoped `TaskDao.getById` read → ledger #1. |
| MR-2 (remaining DAOs) | Zero unscoped `UPDATE`/`DELETE` remain except the two intentionally global ones, headed for the allowlist. Found: `RoomChecklistRepository` still has no guard on its other writes → ledger #4. |
| MR-3 (TaskRepository) | TaskRepositoryImpl has **no** remaining sync bypasses — all six narrow methods verified enqueueing by test. Remaining bypasses: 13 in Notes, 4 in Projects/Tags/TagGroup, which is MR-4/MR-5 scope. |
| MR-4 (NotesRepository) | All 14 note write methods now enqueue. The review surfaced two critical defects the bypass sweep could not see, both fixed here — see "Two defects the class-level sweep could not find" below. |
| MR-5 (Projects/Tags/TagGroup) | All remaining bypasses closed. The serializability defect was **not confined to Notes** — `Project` and `Tag` had it too. The sweep also caught `archiveCompletedTasks`, a bulk `UPDATE` that trashed N tasks and pushed none. |
| MR-6 (fakes) | Fakes now reproduce production, and `FakeRepositoryFidelityTest` (15 tests) pins the contract with a verified positive control. The review then found the *production* task read paths leaking across users — ledger #1, now fixed. It also surfaced a fixture that only passed because the fake was too permissive. |
| MR-7 (backup + exceptions) | The premise was wrong: the ~15 swallowed exceptions were mostly already handled, and the sweep had matched `runCatching`/`getOrNull` without reading the chain. What is real is now documented at each call site and carried in ledger #15/#16. `BackupImporter`'s layer exception is documented where a future reader will actually see it — at the write loop. |
| MR-8 (enforcement) | Two Konsist rules now fail the build: DAO mutations must filter on `user_id`, and `core/` must reach DAO mutations only through a repository that owns them. Both were run against the tree before being trusted — rule 1 immediately found the real `AttachmentDao` gap that the MR-2 sweep had missed. |

### The serializability defect was not confined to Notes

MR-4 found that `Note` lacked `@Serializable` while `toJson()` resolves
`serializer<Note>()`, so every note enqueue threw and was swallowed. The
obvious next question is whether the other syncable types share the defect —
and the honest answer is that MR-4 did not check. MR-5 checked: **`Project`
and `Tag` were both missing the annotation as well.** Three of the five
`SyncableEntity` types therefore never reached the outbox at all.

That makes the invariant worth testing rather than remembering, so
`SyncableEntitySerializationTest` round-trips `toJson()` for every entity and
asserts that every `DocType` is covered — with a positive control, since a
test that cannot fail is not a test.

### Two defects the class-level sweep could not find

Both were found only because MR-4's tests exercised the real path end to end
rather than grepping for bypasses.

**`Note` and `NoteColor` were not `@Serializable`, so notes never synced at all.**
`Note.toJson()` resolves `serializer<Note>()`, which throws when the class is
not `@Serializable`. Production `enqueue` is
repository → `SyncEngine.enqueue` → `buildPatch` → `toJson`, and
`runCatchingResult` swallowed the throw, so **no outbox row was ever written
for a note** — not even through `create`/`update`, which did call `enqueue`.
The bypass methods were the more visible half of the problem; the deeper half
was that the sync path was non-functional for Notes. Fixed by annotating both
classes; the KSP-generated serializer also unblocks the pull path, where
`SyncBootstrapper` decodes into `Note`.

**`toLinksJson` had an unbounded loop.** Inside `buildString` the implicit
receiver is the `StringBuilder`, which is a `CharSequence`, so a bare
`forEachIndexed` bound to `CharSequence.forEachIndexed` rather than to the
list — iterating the builder's own characters *while appending to it*, until
`OutOfMemoryError`. Only the `isEmpty()` short-circuit hid it, so any task
with at least one outgoing link crashed. Both copies (`TaskOutgoingLinks` and
`NotesRepository`) had the same shape.

`TaskOutgoingLinksTest` had been `@Disabled` with a comment attributing this
OOM to the Kover coverage runtime, citing ADR
`2026-09-25-test-jvm-heap-default`. **That diagnosis was wrong** — the OOM
reproduces with Kover off and in complete isolation. The tests had been
switched off rather than the cause fixed; they are re-enabled and pass.

## Known gaps (ledger)

Accumulates from the per-MR phase reviews. Each entry is a finding that was **not** fixed in
the MR that discovered it.

| # | Finding | Status |
|---|---|---|
| 1 | `TaskDao.getById(id)` was unscoped and `TaskRepositoryImpl.get`/`exists` used it, so `get(id)` returned **any user's task** and `exists(id)` disclosed its presence. Deferred in MR-1 as "out of scope, this batch is about writes"; the MR-6 phase review showed it was a real data exposure. **Fixed** via `watchByIdForUser`/`getByIdForUser`/`getTagIdsForUser`/`getDependencyIdsForUser`, with every read path routed through them. | closed |
| 2 | `BackupImporter` (BackupImporter.kt:58-93) writes `taskDao`/`noteDao`/`projectDao`/`tagDao` directly, violating the layer rule. It **cannot** simply route through repositories: import targets an arbitrary `userId` while repositories read the *ambient* scoped user. The two unscoped `@Upsert` cross-ref methods exist solely for it. Correct resolution is a documented Konsist allowlist entry plus, longer term, a real bulk-import port. | open |
| 3 | `TaskDetailViewModelTest.TitleChanged debounce saves after delay` fails on `main` — a 60-second `UncompletedCoroutinesError`, not caused by this work (verified by stashing the changes). Root cause chain: `FakeProfileAwareCurrentUser` defaults to `Dispatchers.Default` so its `scopedUserId` collector is not virtualised, and the VM's save path is `combine(_latestTask.filterNotNull(), titleEdits.debounce(300ms))` over a `MutableSharedFlow(replay = 0)`. Injecting `StandardTestDispatcher(testScheduler)` converts the hang into a fast assertion failure (`expected: <Edited title> but was: <Test task>`), i.e. it removes the 60s stall but does not fix the debounce. Not fixed here: out of scope, and the file documents its own planned fix in ADR `2026-09-25-testable-vm-dispatcher-clock`. **The stated root cause was false** — `560f3bf8` had already moved the default to `Dispatchers.Unconfined`, and that ADR flagged the change as unverified. The test file was deleted by the slot refactor, so the symptom is gone and the cause was never real. Closed as **obsolete, not fixed**: nothing was verified against the original assertion. See `2026-09-28-mr1-test-virtualization-retro.md`. | obsolete — see MR-1 retro |

| 4 | `RoomChecklistRepository` still has no `assertCanWrite` and its remaining writes (`upsert`, `toggleItem`, `createBatch`) bypass ownership entirely. Now that it resolves the ambient user, the DAO layer can enforce these next. | open |
| 5 | `FakeNotesRepository.search` searches title + body while production searches title only. Fixed in the fake-fidelity MR; the underlying question — *should* production search body? — is a product decision, not a correctness one. | open |
| 6 | The initial sweep reported ~15 swallowed exceptions. **Re-checked in MR-7: that was largely wrong** — `DraftMviViewModel:132`, `TaskCreateViewModel:81` and `DataStoreDraftStore:39` already handle failure via `onFailure`/warn logging, and `SearchViewModel`'s `getOrNull` is a documented signal (`activeFilter = null` means "query not expressible as a SimpleFilter"). The genuine remainder is ledger #15 and #16. | corrected |
| 7 | `TagGroupRepositoryImpl.delete` pushed a placeholder `TagGroup(name = "", color = 0)` and `setInheritedForProject` deleted without re-inserting. Both **fixed in MR-5**. | closed |
| 10 | `Project` and `Tag` had the **same** missing-`@Serializable` defect as `Note`, and were fixed in MR-5 — three of the five `SyncableEntity` types never reached the outbox. Now locked down by `SyncableEntitySerializationTest`, which round-trips `toJson()` per entity and asserts every `DocType` is covered (verified with a positive control). **Correction:** the MR-4 entry in this ledger claimed Project and TagGroup were "verified NOT affected". That claim was made without checking them, and was wrong. | closed |
| 11 | ADR `2026-09-25-test-jvm-heap-default.md` attributes the `toLinksJson` OOM to the Kover coverage runtime. That is disproven (it reproduces in isolation, Kover disabled). The ADR is now misleading and should be corrected or superseded. | open |
| 12 | A project's *inherited tag groups* are never synced. They live in the `project_tag_groups` join table and are not a field on `Project`, so there is no entity state to push — `setInheritedForProject` is correct not to enqueue. Whether that relationship *should* converge across devices is a sync-protocol question this batch deliberately does not answer. | open |
| 13 | `FakeTaskRepository()`'s default current user is `UserId("test-user")` while the `testTask` fixture defaults to `UserId.anonymous`, so fixtures and fake disagree about who owns a task. Every task repository is user-scoped, so **anonymous is not a realistic owner** in any repository test. Worth changing one default or the other so the mismatch cannot recur. | open |
| 14 | Other entities' repository read paths were audited only for Tasks. `RoomNotesRepository.get` already used `getByIdForUser`, and Projects/Tags were scoped in MR-6, so no equivalent leak was found — but only Tasks has a read-isolation test. | open |
| 15 | `AttachmentRepository.saveFileAttachment` swallows a `computeChecksum` failure via `getOrNull()`, so `checksum` is silently null and downstream dedupe cannot rely on it. Deliberate — the file is already on disk and failing the whole save over a digest is worse. Surfacing it needs a `Logger` this class does not receive from DI. | open |
| 16 | Intentional fallbacks in the AI tools (`CreateTaskTool`/`UpdateTaskTool` enum coercion, `DecomposeAndCreateTool`'s `catch (Throwable)` and `parsePlan`'s `getOrElse`) swallow failures with no log line, so an agent's bad input or an LLM outage is invisible. Adding logs means threading `Logger` through the tool registry in DI. | open |
| 17 | `calendar_sync_task_map` has no `user_id` column, so its deletes are device-local rather than per-profile. Allowlisted for now; making it per-profile needs a Room migration and a decision on whether calendar state is profile- or account-scoped. | open |
| 18 | A repository that calls a *scoped* DAO mutation is not yet mechanically enforced — the Konsist rule covers the SQL and the core-layer boundary, not "did you pass the right userId at the call site". A detekt rule comparing call sites is the natural follow-up, deferred because the PSI work is disproportionate to the SQL guarantee now in place. | open |
| 8 | Narrow field-update methods (`toggleComplete`, `setPinned`, `setTags`, …) remain separate write paths. Collapsing them into `update(entity)` removes the bug class by construction but introduces a read-modify-write race that partial UPDATEs currently avoid; the correct long-term answer is an optimistic-locking version column. Deliberately deferred — deciding it needs production evidence this batch does not produce. | open |
| 9 | `NotesRepository.kt` is 448 lines mixing interface, impl and mappers, against a detekt `TooManyFunctions` limit of 25 per file. Structural debt, unrelated to correctness. Mappers now live in `NotesMappers.kt` (468 + 94 lines), and the file had grown to 544 / 52 functions. **Still open, and the split does not close it:** the limits that fire are 11 per interface and 11 per class, not the 25 per file recorded here, and the interface alone has 20 methods. Clearing it is an API redesign, so both baseline entries stay. See `2026-09-28-mr3-repository-read-isolation.md`. | open (mappers split; limit is per-declaration) |

## Links

- `docs/decisions/2026-09-21-generic-user-scoped-repository.md` — the base interface
- `docs/decisions/2026-09-24-dao-userid-guards.md` — the `*ForUser` precedent
- `docs/decisions/2026-09-25-no-store-library-local-first-pattern.md` — Room as SoT
- `docs/decisions/2026-09-26-konsist-architecture-tests.md` — the allowlist protocol

---
title: "Post-MR-3 audit findings"
date: 2026-10-01
tags: [audit, mr-3]
status: accepted
---

# Post-MR-3 audit findings

## Cluster 6: Missing assertCanWrite

**AttachmentRepository.create()** — FIXED. Now stamps `currentUser.scopedUserId.value`
instead of accepting caller-supplied userId. This prevents cross-user injection.

**ChecklistRepository** — NOT APPLICABLE. `ChecklistItemEntity` has no `userId` column,
so `assertCanWrite(entityId, entityUserId)` can't be called. The entity has no owner
to assert against. This is a design gap but not a security vulnerability — the
`ChecklistItemEntity` schema itself has no owner field.

**InternalLinkRepository** — Reviewed. All methods are query-only (observe/search),
no write methods. No assertCanWrite needed.

**ArchiveRepository (TaskDaoArchiveRepository)** — Reviewed. `archiveCompletedTasks()`
writes via user-scoped DAO (`taskDao.archiveCompletedForUser(uid)`). No cross-user
injection risk.

**BackupRepository** — Reviewed. `export()`, `import()`, `delete()`, `push()`, `pull()`.
`push()`/`pull()` use `currentUser.scopedUserId.value` for remote storage routing —
not a cross-user write. `import()` is documented LAYER EXCEPTION (direct DAO writes
for bulk restore, intentional). No assertCanWrite gap.

**ReminderRepository / ProjectRemindersRepository** — Already has assertCanWrite on
upsert, proper user scoping on all methods.

## Cluster 5: Fake fidelity

`FakeTaskRepository` correctly applies `TaskDomain.matchesFilter` for All/Inbox paths.
`InMemoryTaskDao` has 33 `error("not implemented")` stubs — all are unreachable DAO
paths that `FakeTaskRepository` never calls (e.g., `watchById`, `watchTrash`).
No production divergence.

The `FakeRepositoryFidelityTest` exists and covers Projects and Tags user isolation.
Additional fidelity test cases for Note, Profile were not written — deferred to follow-up.

## Cluster 7: Direct DAO writes

`BackupImporter` — LAYER EXCEPTION documented in code (lines 57-78 of BackupImporter.kt):
direct DAO writes for bulk restore are intentional, as routing through repositories
would either violate `assertCanWrite` or require temporarily switching the active profile.

`TaskDaoArchiveRepository` — Uses user-scoped DAO, not direct writes.

## Cluster 8: Detekt rules

`NoRealDelayInTest` exists and is active. Cutoff at 500ms means it cannot flag
most real-time delays. Creating tighter rules (`NoEmptyOnClickLambda`,
`NoDirectDispatchersDefault`, `NoDirectDaoWritesFromCore`) is a larger effort
deferred to when the enforcement baseline is settled.

## Detekt baseline failure

`ProjectDetailContent.kt:103` — `LongMethod` (146 > 80) and `CyclomaticComplexMethod`
(complexity 22 > 20). Pre-existing from baseline, not introduced by MR-2/3 changes.
Not fixed — `ProjectDetailViewModel` split (MR-5) will reduce content complexity.

## Items deferred

1. **FakeRepositoryFidelityTest cases** for Note, Profile fidelity — needs dedicated analysis
2. **Detekt rule gaps** — `NoEmptyOnClickLambda`, `NoDirectDispatchersDefault`,
   `NoDirectDaoWritesFromCore` rules need authoring + baseline update
3. **`ChecklistItemEntity` has no userId** — design gap, not a security issue
4. **`NoRealDelayInTest` cutoff** — 500ms too permissive; tighten after baseline settles

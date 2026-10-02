---
title: "Architectural Follow-ups — October 2026 Epic"
date: 2026-10-01
description: Architectural follow-ups from October 2026 epic completion
status: accepted
created: 2026-10-01
---

# Architectural Follow-ups — October 2026 Epic

## Context

Three MRs were completed: Notes↔Tasks logbook (MR-1), Typed task dependencies (MR-2), Celebration overlay (MR-3). During implementation and post-merge audit, several issues were identified that require tracking but are not release-blockers.

---

## 1. `DependencyValidatorImpl.analyzeDependencies` is a stub

**File**: `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/DependencyValidatorImpl.kt:24`

```kotlin
override suspend fun analyzeDependencies(taskId: TaskId): DependencyAnalysis {
    return DependencyAnalysis(containsCycle = false, blockers = emptyList())
}
```

**Problem**: The method signature promises full cycle detection but delivers only a self-loop check. The `taskDao` field is injected but unused. Any UI that calls `analyzeDependencies` gets incorrect data.

**Fix**: Implement BFS traversal over `task_dependencies` cross-ref table in `DependencyValidatorImpl`. Until then, `analyzeDependencies` should either be removed or annotated with `@Deprecated(message = "Stub — full graph traversal deferred to v2")` to prevent accidental use.

**Priority**: Medium — cycle detection UI cannot work correctly without it.

---

## 2. Celebration never fires for recurring task completion

**File**: `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/slot/TaskCompletionSlot.kt:53`

**Problem**: When completing a recurring task, `completeRecurring` use case is invoked and the method returns immediately. The `TaskTitleRow` celebrates based on `isCompleted` state read from `taskFlow`, but for `FROM_COMPLETION` recurring tasks the row briefly reads `isCompleted = false` (the occurrence is rolled forward, not marked done), so no celebration fires. For `FROM_DUE` it may briefly celebrate once on the intermediate state before the UI refreshes.

**Fix**: Add `TaskCompletionIntent.Celebrate` intent that fires explicitly after `completeRecurring` succeeds, independent of the `isCompleted` state projection.

**Priority**: Low — the recurring completion UX is already confusing (no "occurrence completed" feedback), so this is a refinement rather than a bug.

---

## 3. Typed dependency verbs are dropped in sync outbox

**File**: `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/data/TaskRepositoryImpl.kt:254`

**Problem**: `enqueueFresh(from)` reads `task.dependsOn` (the legacy `BLOCKS`-only Set) and ships that to the outbox. Any `FOLLOWS_UP` / `FIXES` / `SUPERSEDES` / `DUPLICATES` edge written via `setDependency` is persisted to `task_dependencies` with the correct `verb`, but lost on sync because the task-level payload only carries `Set<TaskId>` (verb-agnostic).

**Fix**: Either (a) make the outbox payload carry `List<TaskDependency>` instead of `Set<TaskId>`, or (b) add a separate `DependencyOutboxEntry` table that tracks typed edges directly.

**Priority**: Medium — sync will reconstruct BLOCKS edges correctly but lose all other verb types on remote sync.

---

## 4. `TaskDependencyCrossRef` has no sync columns

**File**: `shared/src/commonMain/kotlin/com/singularity/todo/core/database/Entities.kt:80`

**Problem**: `TaskDependencyCrossRef` is a bare join table without `SyncColumns` embedded. Typed dependency edges are local-only. If multi-device sync is ever enabled, the cross-ref table needs `hlc` and `syncStatus` columns.

**Fix**: Add `SyncColumns` to `TaskDependencyCrossRef` in a future schema migration (v26). This is purely additive — no existing data needs to change.

**Priority**: Low — out of scope for current epic.

---

## 5. `TaskDependencyCrossRef` join table has no referential integrity

**File**: `shared/src/commonMain/kotlin/com/singularity/todo/core/database/Entities.kt:80`

**Problem**: `task_id` and `depends_on_task_id` are plain `String` columns with no `ForeignKey` declaration. When a task is deleted, `task_dependencies` rows remain dangling. When a note is linked via `Note.taskId`, deleting the task leaves `taskId` pointing to a non-existent task.

**Fix**: Add `ForeignKey` constraints with `onDelete = CASCADE` for both directions. This requires a schema migration.

**Priority**: Low — data inconsistency is rare in single-user local-first app.

---

## 6. `DependencyValidatorImpl.taskDao` is unused

**File**: `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/DependencyValidatorImpl.kt:14`

**Problem**: `taskDao` is injected into `DependencyValidatorImpl` but neither `assertNoCycles` nor `analyzeDependencies` uses it. The constructor parameter should be removed until the BFS implementation is added.

**Fix**: Remove `taskDao` parameter from `DependencyValidatorImpl` constructor and its `DependencyValidator` interface.

**Priority**: Low — dead code that creates false expectations.

---

## 7. `FakeTaskRepository.setDependency` lacks failure-path override

**File**: `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeRepositories.kt:693`

**Problem**: `FakeTaskRepository` has `setDependenciesOverride: Result<Unit>?` (for the legacy method) but no equivalent for the new `setDependency` method. Tests that want to exercise the error path must use a different mechanism.

**Fix**: Add `setDependencyOverride: Result<Unit>?` to `FakeTaskRepository` alongside the new method.

**Priority**: Low — existing tests cover the happy path.

---

## 8. `TaskCompletionSlot` has no `onSaved` callback

**File**: `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/slot/TaskCompletionSlot.kt`

**Problem**: Other slots (`TaskChildrenSlot`, `TaskLifecycleSlot`) call `::reportSaved` on success. `TaskCompletionSlot` calls `onFailure` only. A successful completion (including recurring) produces no "Saved" snackbar feedback.

**Fix**: Add `onSaved` callback to `TaskCompletionSlot` and call it after `completeRecurring` or `updateTask` succeeds.

**Priority**: Low — UX polish, not a correctness bug.

---

## 9. `NoteEntity.task_id` and `outgoing_links` encode the same link two ways

**File**: `shared/src/commonMain/kotlin/com/singularity/todo/core/database/Entities.kt:92`

**Problem**: A note can be linked to a task via (a) `NoteEntity.task_id` column (the new way, added in MR-1) and (b) the `outgoing_links` JSON column containing `task://` wikilinks (the legacy way). Both exist simultaneously with no referential integrity. This means a note can link to a task in two different ways, with no enforcement that they agree.

**Fix**: Migrate all `task://` links from `outgoing_links` to `task_id` column, then remove the wikilink parsing from `NotesRepositoryImpl`. Keep `task_id` as a proper FK with `onDelete = SET_NULL`.

**Priority**: Medium — technical debt that makes the data model harder to reason about.

---

## Non-issues (documented for future reference)

- **Celebration trigger key in `ChecklistItemRow`** uses `text` as triggerKey — acknowledged in code comment. Acceptable because checklist items have no stable ID in the current API. Fix requires a `ChecklistItemId` type (out of scope).
- **`koinInject<Haptic>()` in previews** — resolved in MR-3 follow-up with `LocalInspectionMode.current` guard. No further action needed.
- **`TaskCard.onToggle` not wired in archive/search/project screens** — intentional design per `TaskCardActions` KDoc. Archive and Search are read-only by design. `ProjectDetailContent` may be an oversight (see issue #4 above), but the primary completion path is `TaskDetailViewScreen` / `TaskTitleRow`.

---

## Links

- MR-1: Notes↔Tasks logbook
- MR-2: Typed task dependency links
- MR-3: Celebration overlay
- ADR: `2026-10-01-typed-task-dependency-links.md`

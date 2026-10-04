---
title: Bulk Operations Use Case — Unwired in ViewModel
status: deferred
status-was: pending  # non-vocabulary value, normalized 2026-10-05
deciders: product owner
impact: medium
date: 2026-10-01
---

## Context

`TaskMutationsUseCase` (`shared/src/commonMain/.../tasks/domain/usecase/TaskMutations.kt`)
is a fully implemented, DI-registered, and unit-tested use case:

```kotlin
class TaskMutationsUseCase(private val repo: TaskRepository) {
    suspend fun bulkComplete(ids: List<TaskId>): Result<Unit>
    suspend fun bulkDelete(ids: List<TaskId>): Result<Unit>
}
```

It is registered in `TasksDiModule.kt:80` as `factoryOf(::TaskMutationsUseCase)`.

**No ViewModel in the tasks feature injects or calls it.** The tasks feature has no
`selectedIds` / selection-mode state. `find-unwired-surfaces.py` does not currently
flag it (the script only checks `@Composable` surfaces), but it matches the pattern:
"feature compiles, is tested, does nothing."

The notes feature (`NotesListViewModel`) has a selection mode (`_selectedIds`,
`_isSelectionMode`, intents `EnterSelection` / `ToggleSelection` / `ExitSelection`)
but the `deleteSelected()` action is private and appears to be dead code — the
notes UI in `NotesActions.kt` only exposes selection toggle, not bulk delete.

## Idea

Wire `TaskMutationsUseCase` into the appropriate ViewModel(s) to expose bulk
complete / bulk delete to the UI. The implementation is done; the surface is missing.

### Option A — Tasks: contextual multi-select

Add `selectedIds: MutableStateFlow<Set<TaskId>>` + `isSelectionMode` to
`TasksViewModel` (or the appropriate list VM). Expose bulk complete / delete
as FAB actions when in selection mode. No new use case needed — just inject
`TaskMutationsUseCase` and call it from the appropriate intent branch.

**Pros:** consistent with how notes tried to do it; natural UX pattern.
**Cons:** requires adding selection state to tasks VM; more surface area.

### Option B — Tasks: swipe action for bulk complete

Use the existing swipe infrastructure (`SwipeAction`) to add a "complete all
overdue" or "complete selected" swipe. Requires adding a selection mechanism
still, or limiting to "all in section."

**Pros:** reuses existing swipe infrastructure.
**Cons:** swipe is single-item; bulk complete needs multi-select.

### Option C — Archive: auto-archive completed

`ArchiveRepository.archiveCompletedTasks()` already exists and is wired to
`ArchiveViewModel`. This is the only bulk operation that currently has a
working UI trigger ("Archive completed" action).

**Pros:** already works.
**Cons:** not user-initiated bulk; only for completed tasks.

## Decision

**Pending product decision.** The use case infrastructure is ready. The
product owner needs to decide:

1. Is task multi-select a priority for the next sprint?
2. If yes — should it live on the tasks list screen or the agenda screen?
3. Should bulk delete be included alongside bulk complete, or defer?

## Consequences

- `TaskMutationsUseCase` stays as-is (already correct).
- The ADR is resolved once a ViewModel is wired to it and the UI ships.
- The notes `deleteSelected()` dead code should be audited separately — either
  wire it or remove it.

## Links

- `TaskMutationsUseCase` — `shared/src/commonMain/.../tasks/domain/usecase/TaskMutations.kt`
- DI registration — `shared/src/commonMain/.../core/di/TasksDiModule.kt:80`
- Test — `shared/src/jvmTest/.../tasks/usecase/TaskMutationsUseCaseTest.kt`

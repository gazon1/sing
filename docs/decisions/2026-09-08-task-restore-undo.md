---
title: "TaskRepository.restore + UndoDelete via SnackbarHost"
date: 2026-09-08
tags: [task-detail, undo, ux]
status: accepted
---

## Context

Task deletion in `TaskDetailViewModel` currently calls `taskRepo.softDelete(id)` and immediately navigates back. There is no way to undo a delete. TickTick, Todoist, and Things 3 all provide an undo window (typically 4 seconds via Snackbar).

## Decision

1. **`TaskRepository.restore(Task): Result<Unit>`** — re-inserts a soft-deleted task by ID, clearing `archivedAt`. Fails if the task does not exist or is not archived.
2. **In-memory `_recentlyDeleted: MutableStateFlow<Task?>`** in `TaskDetailViewModel` — holds the last deleted task. Cleared on successful undo or on `onCleared()`.
3. **`TaskDetailUiEvent.UndoDelete(taskId)`** emitted instead of immediate `NavigateBack`. The screen shows a Snackbar with "Task deleted [Undo]" for 4 seconds. On Undo tap, `restore()` is called and `_recentlyDeleted` is cleared.
4. **Archive is soft-delete without undo** — `archiveTask()` does not store the task in `_recentlyDeleted` (archive is intentional and can be restored from Trash separately).

## Consequences

- **Always** use `SnackbarHost` + `SnackbarHostState` for undo, not `AlertDialog`.
- **Never** store more than one recently-deleted task in memory — the most recent overwrite.
- `_recentlyDeleted` must be cleared in `onCleared()` to avoid leaking task data on configuration change.
- `restore()` re-uses the original `id` — idempotent by design.

## Links

- `feature/tasks/TaskDetailViewModel.kt` (_recentlyDeleted, undoDelete)
- `feature/tasks/TaskRepository.kt` (restore method)
- Skill: `singularity-todo-ui-event-vs-state`

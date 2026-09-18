---
title: "Task archive vs delete: separate contracts via archiveAt"
date: 2026-09-08
tags: [task-detail, archive, repository]
status: accepted
---

## Context

`TaskRepository.softDelete(id)` is currently called identically for both "Archive" and "Delete" actions — the only difference is the UI string. `Task.isTrashed` is defined as `archivedAt != null` but the `archive()` path did not explicitly set `archivedAt`.

## Decision

1. **`TaskRepository.archive(id): Result<Unit>`** — explicitly sets `archivedAt = clock.now()`. Distinct from soft-delete.
2. **`TaskRepository.softDelete(id)`** (existing) — sets `archivedAt` implicitly (soft-delete = trash). Behavior unchanged for callers that don't use `archive()`.
3. **TaskDetailViewModel:** `archiveTask()` calls `repo.archive(id)`. `deleteTask()` calls `repo.softDelete(id)`. Archive has no confirm dialog (undo snackbar is sufficient). Delete requires confirm bottom-sheet.
4. **`restore(Task): Result<Unit>`** — clears `archivedAt` by re-inserting with the original id. Used only by Undo in TaskDetailViewModel.

## Consequences

- Archive and Delete have distinct storage semantics — future "Trash" filter can distinguish intentional archive from accidental delete.
- `restore()` is idempotent — calling restore on a non-archived task is a no-op (or returns Result.success if the row simply re-inserted).
- `_recentlyDeleted` in TaskDetailViewModel holds the full task before delete/archive for undo.

## Links

- `feature/tasks/TaskRepository.kt` (archive, restore, softDelete)
- `feature/tasks/TaskDetailViewModel.kt` (archiveTask, deleteTask)
- ADR: `2026-09-07-task-detail-archive-overflow` (previous archive implementation)

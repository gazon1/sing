---
title: "ProjectDetailViewModel: write-through + _latestProject TOCTOU guard"
date: 2026-09-09
tags: [project-detail, toctou, write-through, vm, regression]
---

## Context

`ProjectDetailViewModel.toggleArchive` (lines 197-201) reads `state.value` snapshot to determine current `isDeleted`:

```kotlin
fun toggleArchive() = viewModelScope.launch {
    val current = (state.value as? ProjectDetailUiState.Content)?.ui?.project ?: return@launch
    updateProject(projectId) { it.copy(isDeleted = !current.isDeleted) }
    _events.emit(ProjectDetailUiEvent.Saved)
}
```

If the UI re-renders between the user's tap and `viewModelScope.launch`, `state.value` may hold a stale snapshot — the debounce collector for `updateName` may have just fired, updating the Room entity but not yet the combined `state` flow. The result: `current.isDeleted` is the pre-debounce value, and `toggleArchive` flips to the wrong boolean.

This is **Regression #6** documented in `singularity-todo-task-detail-ux`: the same TOCTOU pattern that was fixed in `TaskDetailViewModel` via `_latestTask` write-through cache.

## Idea

Fix by introducing `private val _latestProject = MutableStateFlow<Project?>(null)` — a write-through cache that always holds the latest emitted value from `projectRepo.watchProject(projectId)`. All mutation methods read from `_latestProject.value` instead of `state.value`.

Alternative considered: read from `projectFlow.value` directly. Rejected because `projectFlow` is `StateFlow<Project?>`, whose `.value` during a suspended coroutine may also be stale if the flow's upstream has not yet emitted.

## Decision

`ProjectDetailViewModel` adopts the write-through pattern from `TaskDetailViewModel`:

1. **`_latestProject`** — `MutableStateFlow<Project?>(null)`, updated in `init {}` via `projectFlow.collect { _latestProject.value = it }`.
2. **All mutation methods** (`handleToggleArchive`, `handleDelete`, `handleUpdateName`, etc.) read `val current = _latestProject.value ?: return`.
3. **`toggleArchive`** now reads `_latestProject.value.isDeleted`, not `state.value`'s snapshot.
4. **`createTask`** routed through `CreateTaskUseCase` (previously bypassed use-case layer with direct `taskRepo.create`).
5. Debounced `updateName` / `updateDescription` silent saves — no `Saved` event emitted (per `ui-event-vs-state` rule).

## Rationale

- `_latestProject` is the single source of truth for the current project state, updated synchronously before any downstream `combine` in `state`.
- TOCTOU is eliminated because `_latestProject` is written before the `combine` step that feeds `state`.
- Using `MutableStateFlow` (not `MutableSharedFlow`) means `.value` is always available — no `.first()` needed.
- Routing state (`activeSheet: ActiveSheet?`) stays in screen, not VM — screen owns routing per `ui-event-vs-state` skill.

## Consequences

- **Always** read entity state from the write-through `_latest<Entity>` cache, never from `state.value` snapshot in mutation methods.
- **Always** update `_latest<Entity>` before any async operation that reads it.
- **Never** emit `Saved` events for debounced inline edits — update `_lastEditedAt` only.
- **`createTask`** must go through `CreateTaskUseCase`, not direct `taskRepo.create`.
- Screen owns `activeSheet` routing state; VM only receives routing intents.

## Links

- Regression #6: `singularity-todo-task-detail-ux` skill
- TOCTOU fix precedent: `2026-09-08-task-detail-critical-fixes.md`
- `_latestTask` pattern: `feature/tasks/TaskDetailViewModel.kt:95-96`
- Existing ADR covering the Intent pattern: `2026-09-09-project-detail-intent-refactor.md`

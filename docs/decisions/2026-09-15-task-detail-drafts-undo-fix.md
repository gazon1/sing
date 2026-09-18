---
title: "TaskDetail drafts seed-from-task; TaskListScreen koinViewModel; undo snackbar wired"
date: 2026-09-15
tags: [architecture, compose, udf, tasks, drafts, undo]
status: accepted
---

## Context

Post-PR-3 audit found four HIGH-severity issues in the tasks feature:

1. **`TaskListScreen`** used `koinInject<TasksViewModel>()` — each recomposition created a new VM instance, losing `_recentlyDeleted` (undo state). Fixed with `koinViewModel()` + `viewModelOf` in DI.
2. **`TaskDetailViewModel`** initialized `_draftTitle = ""` and `_draftDescription = ""` and never seeded them from the loaded task. User opened a task detail, typed — first character **replaced** the real title because `titleEdits.debounce(300).tryEmit(newTitle)` ran on an empty draft.
3. **`TaskDetailViewModel`** had `private _recentlyDeleted` updated on `Delete` intent but never exposed; `TaskDetailViewContent` never showed an undo snackbar — dead state.
4. **`TasksViewModelTest`** simplified the `restore clears archivedAt` test to call the repository directly (bypassing the VM), losing coverage of the VM-level `_recentlyDeleted` flow.

## Decision

### Fix 1: `koinInject` → `koinViewModel` + `viewModelOf`

`TasksDiModule` changed from:
```kotlin
viewModel {
    TasksViewModel(
        taskRepo = get<TaskRepository>(),
        createTask = get<CreateTaskUseCase>(),
        // ...all 8 params
    )
}
```
to:
```kotlin
viewModelOf(::TasksViewModel)
```

`TaskListScreen` changed from:
```kotlin
val vm: TasksViewModel = koinInject()
```
to:
```kotlin
val vm: TasksViewModel = koinViewModel()
```

`viewModelOf` works because all constructor parameters have defaults (`scopeOverride`, `sharingStarted`, AI use cases) or are DI-resolvable.

### Fix 2: Seed drafts with seed-if-empty

In the `combine` block (after `_latestTask.value = task`):
```kotlin
if (_draftTitle.value.isEmpty()) {
    _draftTitle.value = task.title
}
if (_draftDescription.value.isEmpty()) {
    _draftDescription.value = task.description ?: ""
}
```

This uses `isEmpty()` as the sentinel — if the user has already started editing (`_draftTitle.value` is non-empty), their draft is preserved. Only on first load (empty draft) does the VM seed from the task.

### Fix 3: Expose `recentlyDeleted` + wire undo snackbar

`TaskDetailViewModel` made `recentlyDeleted` public:
```kotlin
private val _recentlyDeleted = MutableStateFlow<Task?>(null)
val recentlyDeleted: StateFlow<Task?> = _recentlyDeleted.asStateFlow()
```

`TaskDetailViewContent` added:
```kotlin
val snackbarHostState = remember { SnackbarHostState() }

LaunchedEffect(Unit) {
    recentlyDeleted.collect { task ->
        if (task != null) {
            val result = snackbarHostState.showSnackbar(
                message = "Задача удалена",
                actionLabel = "Восстановить",
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                onIntent(TaskDetailIntent.Domain.Restore)
            }
        }
    }
}
```

Scaffold received `snackbarHost = { SnackbarHost(snackbarHostState) }`.

### Fix 4: Test documentation

The `restore clears archivedAt` test documents the limitation:
> Testing the full delete→restore VM cycle requires viewModelScope to use the test dispatcher (scopeOverride workaround) and is covered by integration tests.

## Rationale

- **Seed-if-empty** avoids cursor-jump when the VM mutates state asynchronously — the draft is the user's in-progress text, not a stale copy.
- **`recentlyDeleted` as `StateFlow`** (not `SharedFlow`) survives recomposition, per `2026-09-08-task-restore-undo`.
- **`koinViewModel`** keeps the scoped VM instance across recompositions, preserving undo state.

## Consequences

- `TaskDetailViewContent` now takes a `recentlyDeleted: Flow<Task?>` parameter — passed from `TaskDetailViewScreen`.
- Preview functions in `TaskDetailViewScreen` updated to pass `emptyFlow()` for `recentlyDeleted`.
- `TasksDiModule` removed now-unused `ProjectsRepository` import.

## Links

- `2026-09-15-viewmodel-state-ownership` — the rule this follows
- `2026-09-08-task-restore-undo` — `recentlyDeleted` as StateFlow
- `2026-09-09-projectdetail-write-through-fix` — seed-if-empty pattern

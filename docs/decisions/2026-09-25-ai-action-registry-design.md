---
status: accepted
date: 2026-09-25
---

# Task AI Action Registry Design

## Context

Phase C wires the 5 existing AI use cases (`RefineTask`, `GenerateDescription`, `GenerateChecklist`, `DecomposeTask`, `PickTime`) into `TaskDetailViewModel` via a `RunAiAction(TaskAiAction)` intent. Prior to this, the use cases were registered in DI but had zero call sites.

Additionally, `TaskAiBottomSheet` (already implemented) was not wired to any callback — `TaskDetailViewScreen` had no `onAiClick`.

## Decision

### Nullable deps pattern (solves final-class inheritance)

The 5 AI use cases extend `LlmUseCase<I, O>` whose `invoke()` is `operator fun suspend invoke(...)`. Since `LlmUseCase` is **not** `open`, it cannot be stubbed by subclassing in tests.

Solution: all AI deps in `TaskDetailDeps` are **nullable** with `= null` defaults:

```kotlin
class TaskDetailDeps(
    val taskRepo: TaskRepository,
    val linkRepo: InternalLinkRepository? = null,
    val cycleValidator: CycleValidator? = null,
    val refineTask: RefineTaskUseCase? = null,
    val generateDescription: GenerateDescriptionUseCase? = null,
    val generateChecklist: GenerateChecklistUseCase? = null,
    val decomposeTask: DecomposeTaskUseCase? = null,
    val pickTime: PickTimeUseCase? = null,
    val log: Logger = Logger.withTag("TaskDetail"),
)
```

`TaskDetailDeps` is passed as a single DI bundle to `TaskDetailViewModel`, matching the `TaskCreateViewModel` pattern.

### Handler uses safe-call with explicit failure

```kotlin
TaskAiAction.RefineTitle -> deps.refineTask
    ?.invoke(current.title, current.description)
    ?.map { newTitle -> ... }
    ?: Result.failure(IllegalStateException("RefineTaskUseCase not available"))
```

When AI is unavailable, `runAiAction` emits an `UiEvent.ShowError("AI not available")` rather than crashing.

### Backlink loading via scope.launch

Backlink queries (`getNotesLinkingToTask`, `getBacklinkTasks`) are launched in `init {}` via `scope.launch` collecting `_latestTask`, using safe-call on `deps.linkRepo`.

### DI wiring

```kotlin
TaskDetailDeps(
    taskRepo = get(),
    linkRepo = get(),
    cycleValidator = getOrNull(),
    refineTask = get(),
    generateDescription = get(),
    generateChecklist = get(),
    decomposeTask = get(),
    pickTime = get(),
)
```

### TaskEditorCallbacks.onAiClick slot

```kotlin
data class TaskEditorCallbacks(
    val onSave: () -> Unit = {},
    val onAiClick: (() -> Unit)? = null,  // nullable — AI may be unavailable
    ...
)
```

`TaskDetailViewScreen` wires `onAiClick` to show `TaskAiBottomSheet`.

## Consequences

- `TaskDetailViewModelTest` removed 5 broken `StubRefineTaskUseCase` etc. class definitions — tests use nullable defaults instead.
- `TaskDetail.RunAiAction` is a one-shot action returning via `_events` SharedFlow.
- AI actions do **not** appear in `TaskEditorMenuBuilder` menu — they remain accessible only from `TaskAiBottomSheet` (accessed via FAB icon on `TaskDetail`).

## Links

- `TaskDetailDeps`, `TaskDetailIntent.RunAiAction`, `TaskDetail.RunAiAction`
- `TasksDiModule.kt` (DI wiring)
- `TaskDetailViewScreen.kt` (`onAiClick` → sheet)

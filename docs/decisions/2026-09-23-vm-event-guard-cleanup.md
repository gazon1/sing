---
title: "VM event/guard cleanup — compareAndSet, typed combine, SendChannel, dead code"
date: 2026-09-23
tags: [vm, concurrency, cleanup]
status: accepted
---

## Context

Static analysis of `TaskCreateViewModel`, `TaskDetailViewModel`, and `SyncViewModel` surfaced three categories of issues:

1. **TOCTOU save guard** — `if (_isSaving.value) return` is a check-then-act race: two coroutines can pass the guard simultaneously before either sets the flag.
2. **Unchecked cast in typed combine** — `TaskDetailViewModel` combines 8 `Flow` sources using the vararg `combine` with an array accessor `values[i] as T` and 8 `@Suppress("UNCHECKED_CAST")` annotations.
3. **Leaky abstraction on Channel parameter** — `NoteSaver.invoke()` declares `events: Channel<NotesUiEvent>` rather than `SendChannel<NotesUiEvent>`, exposing the concrete implementation type in the API.

Separately, batch audit found two items of dead code with zero callers:
- `TaskDetailMode.kt` (sealed interface, never matched or branched on)
- `Attachment` sealed sub-hierarchy inside `TaskDetailIntent` (UI routes attachment events to `AttachmentsViewModel`, not through `TaskDetailIntent`)

## Decision

### 1. `compareAndSet` for save guard (replaces TOCTOU check)

**File:** `TaskCreateViewModel.kt`

```kotlin
// Before (TOCTOU race):
if (_isSaving.value) return
_isSaving.value = true
try { save() } finally { _isSaving.value = false }

// After (atomic):
if (!_isSaving.compareAndSet(expect = false, update = true)) return
try { save() } finally { _isSaving.set(false) }
```

`compareAndSet` atomically transitions `false → true`; the second caller finds `true` already and returns immediately. This eliminates the window between the check and the set.

**File:** `SyncViewModel.kt` — compound-condition guard via `Mutex.withLock`:

```kotlin
private val syncMutex = Mutex()

private fun syncNow() {
    scope.launch {
        syncMutex.withLock {
            if (_state.value.isLoading || _state.value.status.isRunning()) return@launch
            // ...
        }
    }
}
```

`Mutex.withLock` serialises concurrent calls; the guard inside prevents no-op syncs when one is already in progress.

### 2. Type-safe nested `combine` (eliminates `UNCHECKED_CAST`)

**File:** `TaskDetailViewModel.kt`

Replaced the 8-source vararg `combine(values: Array<Any?>)` with three intermediate typed data classes and nested `combine` calls:

```kotlin
private data class Meta(
    val project: Project?,
    val allTags: List<Tag>,
)
private data class Content(
    val checklist: List<ChecklistItem>,
    val reminders: List<Reminder>,
    val attachments: List<Attachment>,
    val subtasks: List<Task>,
    val available: List<Task>,
)
private data class AllData(
    val meta: Meta,
    val content: Content,
    val draft: TaskDetailDraft,
)

private val metaFlow = combine(projectFlow, tagsFlow) { p, t -> Meta(p, t) }
private val contentFlow = combine(checklistFlow, reminderFlow, attachmentsFlow, subtasksFlow, availableTasksFlow) {
    c, r, a, s, av -> Content(c, r, a, s, av)
}
private val allFlow = combine(metaFlow, contentFlow, draftState.state) { m, c, d -> AllData(m, c, d) }

val uiState: StateFlow<TaskDetailUiState> = allFlow.combine(flowOf(task)) { all, t ->
    TaskDetailUiState.Loaded(TaskDetailUi(... all.meta.project ... all.content.checklist ...))
}
```

Result: 8 `@Suppress("UNCHECKED_CAST")` removed, zero unchecked casts remain. The public API is unchanged — only internal mechanics.

### 3. `SendChannel<UiEvent>` instead of `Channel<UiEvent>`

**File:** `NoteSaver.kt`

```kotlin
// Before:
internal class NoteSaver(private val events: Channel<NotesUiEvent>, ...)

// After:
internal class NoteSaver(private val events: SendChannel<NotesUiEvent>, ...)
```

`Channel` implements `SendChannel`; callers pass a concrete `Channel` as before. The narrower type signals that `NoteSaver` only sends, never closes or reads from the channel.

### 4. Dead code removal

- **`TaskDetailMode.kt`** — deleted (3-line sealed interface, 0 usages).
- **`Attachment` branch in `TaskDetailIntent`** — deleted (UI events `Delete`, `Click`, `PickFile` are routed directly to `AttachmentsViewModel`; they never enter `TaskDetailIntent`).

## What Was Considered But Not Done

| Idea | Reason to defer |
|---|---|
| `Channel(BUFFERED)` → `SharedFlow(replay = 1)` | `Channel(BUFFERED)` is the correct pattern for one-shot UI events per Google NIA. `SharedFlow(replay = 1)` re-emits on config changes, causing double-dispatch. No change needed. |
| `TaskDetailLoader` as a separate class | Over-engineering. Typed `combine` is sufficient; a separate class adds indirection without solving a real problem. |
| `mergedWith` pure function for conflict resolution | Sync engine already handles per-field merge. Adding a VM-layer merge function creates a second mechanism. |
| Per-task `PendingTaskSaves` lock map | No scenario for simultaneous editors on the same task in the current UX. Can be revisited if that changes. |
| Refresh-from-db in VM on save | Without a save-lock this is a half-measure. Belongs in the data layer with proper concurrency control. |

## Consequences

- Double-tap on Save creates exactly one entity (compareAndSet enforces single-writer).
- `TaskDetailViewModel` typed combine is readable without `@Suppress` annotations.
- `NoteSaver` API contract is precise: it sends, never manages the channel lifecycle.
- Dead code removed — `TaskDetailMode` and the `Attachment` intent branch would have required maintenance with zero benefit.

## Links

- Google NIA on one-shot events: `Channel(BUFFERED)` over `SharedFlow(replay = 1)`
- Kotlin coroutines `compareAndSet` docs
- `Mutex.withLock` for compound-condition guards (sync vs. `_isSaving` flag)
- Kotlin coroutines `combine` 5-arg overload for typed intermediate results

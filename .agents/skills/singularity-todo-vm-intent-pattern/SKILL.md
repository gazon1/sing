---
name: singularity-todo-vm-intent-pattern
description: The house ViewModel pattern for this project: sealed Intent + single onIntent dispatcher, routing vs domain intent separation, _latestTask cache, and when to use (or skip) a pure reducer.
---

# ViewModel Intent Pattern

This project uses a **typed Intent** pattern for all ViewModels. The pattern is documented in AGENTS.md ("`*ViewModel.kt` — `StateFlow<SealedUiState>`, **sealed Intent**, viewModelScope") and implemented across the codebase.

This skill captures the pattern, the common pitfalls, and the key design split — **when a pure reducer is appropriate and when it is not**.

**Three variants of the pattern:**

- **Full** (has routing intents for sheets): `TaskDetailViewModel`, `TaskEditorViewModel`
- **Minimal** (no sheets — only domain mutations + navigation): `ProjectDetailViewModel`
- **Evaluate-only** (static definition, no intents): `AgendaViewModel` — definition is injected at construction time; the VM watches all tasks and evaluates them purely in-memory with `AgendaEvaluator.evaluate()`. No routing intents, no mutation intents — only `AgendaIntent.TaskClicked` for navigation.

---

## The Core Pattern

### 1. Sealed Intent interface

```kotlin
sealed interface XxxIntent {
    // ── Routing (owned by screen) ──────────────────────────────────────────
    // These never reach the VM — the screen handles them directly.
    data class OpenSheet(val sheet: Sheet) : XxxIntent
    data class NavigateTo(val id: EntityId) : XxxIntent

    // ── Domain (owned by VM) ────────────────────────────────────────────
    sealed interface Domain : XxxIntent {
        data object ToggleComplete : Domain
        data class SetPriority(val priority: Priority) : Domain
        data class SetTitle(val title: String) : Domain
        // … all write-through mutations
    }
}
```

### 2. Single dispatcher in VM

```kotlin
class XxxViewModel(...) : ViewModel() {
    fun onIntent(intent: XxxIntent.Domain) {
        val current = _latestTask.value ?: return   // ← safety guard
        when (intent) {
            is XxxIntent.Domain.ToggleComplete -> toggle(current)
            is XxxIntent.Domain.SetTitle -> setTitle(current, intent.title)
            // …
        }
    }
}
```

### 3. Single dispatcher on screen

```kotlin
val actions = remember { XxxActions { intent ->
    when (intent) {
        is XxxIntent.OpenSheet   -> activeSheet = intent.sheet
        is XxxIntent.NavigateTo  -> onNavigate(intent.id)
        is XxxIntent.Domain      -> viewModel.onIntent(intent)   // ← boundary
    }
} }
```

The key property: **routing intents are type-forbidden from reaching the VM**. The compiler enforces the boundary.

---

## When a Pure Reducer Is Appropriate

Use a pure reducer (like `TaskEditorUiState.reduce(intent)`) when:

- The screen has **local draft state** that accumulates before persisting (e.g., a task editor where the user types before clicking Save).
- You want `TaskEditorReducerTest` — a unit test that calls `state.reduce(intent)` with no mocks, no DI, no ViewModel.

```kotlin
// TaskEditorUiState — local draft, no persistence until Save
data class TaskEditorUiState(
    val title: String = "",
    val description: String? = null,
    val priority: TaskPriority = TaskPriority.None,
    // …
)

internal fun TaskEditorUiState.reduce(intent: TaskEditorIntent): TaskEditorUiState = when (intent) {
    is TaskEditorIntent.TitleChanged -> copy(title = intent.text)
    // pure — no IO
}

class TaskEditorViewModel(...) : ViewModel() {
    fun onIntent(intent: TaskEditorIntent) {
        _uiState.update { it.reduce(intent) }
        // IO side effects in separate when-branches
        when (intent) {
            TaskEditorIntent.Save -> save()
            // …
        }
    }
}
```

---

## When a Pure Reducer Is NOT Appropriate (Cargo Cult Warning)

**Do not** add a reducer when the UI state is entirely derived from a repository `Flow`. `TaskDetailUi` is a Room-read-model: it has no local draft, no "dirty" flag, no pending-changes buffer. All fields come from `taskRepo.watchTask(id)` combined with five other flows.

Adding `TaskDetailUi.reduce()` here would be cargo cult — it would look like `TaskEditorUiState.reduce()` but perform no real work:

```kotlin
// WRONG for TaskDetail — there is no local draft to reduce
internal fun TaskDetailUi.reduce(intent: TaskDetailIntent.Domain): TaskDetailUi = when (intent) {
    is SetPriority -> copy(task = task.copy(priority = intent.priority))  // ← mutates!
    // This is NOT a pure reducer — it calls .copy() on domain objects
}
```

The correct approach for read-through screens: **dispatch directly to `mutate{}`** from `onIntent`, as shown above. No `_uiState.update {}` needed.

---

## The _latestTask Cache — Why It Exists

The `_latestTask: MutableStateFlow<Task?>` cache solves a TOCTOU race:

```kotlin
// BAD: reads from UI snapshot — concurrent remote edit is silently lost
is SetPriority -> scope.launch {
    updateTask(current.copy(priority = intent.priority))   // current from UI
}

// GOOD: reads from VM's authoritative cache — remote edits are preserved
is SetPriority -> mutate(current) { copy(priority = intent.priority) }
```

The `mutate {}` helper reads `_latestTask.value ?: return` — the same value that the `combine` block in `stateIn` populates on every upstream emission.

---

## Mistake: Remember without Keys on Actions

```kotlin
// WRONG — lambda recreated on every state change, all sections recompose
val actions = remember(ui) { XxxActions { intent -> ... } }

// CORRECT — lambda is stable; sections only recompose on actual intent
val actions = remember { XxxActions { intent -> ... } }
```

The `value class` wrapper gives referential stability. `remember(ui)` defeats it.

---

## Mistake: Routing Intent Through VM

```kotlin
// WRONG — VM knows about sheets (violates "screens own routing state")
fun openDatePicker() = scope.launch { _events.emit(TaskDetailUiEvent.OpenDatePicker) }

// CORRECT — screen sets its own routing state
val actions = XxxActions { intent ->
    when (intent) {
        is XxxIntent.OpenSheet -> activeSheet = intent.sheet   // screen-only
        is XxxIntent.Domain -> viewModel.onIntent(intent)
    }
}
```

When in doubt: if the operation does not touch the repository, it does not belong in the VM.

---

## Reference Implementation

- `TaskEditorViewModel` — draft-editor pattern with pure `reduce()` + `TaskEditorReducerTest`
- `TaskDetailViewModel` — write-through pattern with single `onIntent()` + `mutate{}`; has sheet routing intents
- `ProjectDetailViewModel` — **minimal variant**: no sheets, only domain intents + navigation callbacks; demonstrates the pattern at its simplest
- `AgendaViewModel` — **evaluate-only variant**: `definition` injected at construction; watches all tasks via `TaskFilter.All`, evaluates in-process with `AgendaEvaluator.evaluate()`, emits `AgendaUiState`. No mutation intents — navigation only via `AgendaUiEvent.NavigateToTask`.
- `TaskDetailIntent.kt` — routing/domain separation in a real-world screen

## See Also

- `singularity-todo-task-callback-groups` — pairing this pattern with `@JvmInline value class Actions` in Composables
- `singularity-todo-ui-event-vs-state` — routing state (which sheet is open) is NOT a `SharedFlow` event
- `docs/decisions/2026-09-09-task-detail-intent-refactor.md` — the ADR that formalized this pattern
- `docs/decisions/2026-09-09-project-detail-intent-refactor.md` — the minimal variant ADR (no sheets)

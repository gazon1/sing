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
- **Evaluate-only (static definition, no intents)** — pure read-through variant: `AgendaViewModel`. Definition is injected at construction; the VM watches all tasks and evaluates them in-memory with `AgendaEvaluator.evaluate()`. No routing intents, no mutation intents, no editable draft state. **This is the canonical case where `combine + stateIn(WhileSubscribed)` IS appropriate** — the state is purely derived from upstream flows with no init logic, no drafts, and no local mutations. Tests for this variant do need Turbine (see `singularity-todo-testable-vm`: "When to Use Each Pattern").

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

`mutate {}` reads `_latestTask.value ?: return` before writing. Without a cache, `current` would come from the last value the *screen* observed — which can be stale by the time the user's tap reaches the VM, if a remote edit landed in between. `_latestTask` guarantees `mutate {}` always reads the VM's own most recent view of the entity, not a UI snapshot.

### How `_latestTask` gets populated — and why this does NOT violate "no side effects in combine"

`_latestTask` is populated by a **dedicated `onEach`**, not by a side effect buried inside the `combine` that produces UI state:

```kotlin
init {
    scope.launch {
        taskRepo.watchTask(taskId)
            .onEach { _latestTask.value = it }          // ← the ONLY writer; pure passthrough cache
            .combine(otherFlow) { task, other -> buildUiState(task, other) }
            .collect { _state.value = it }
    }
}
```

This is a narrow, explicitly-allowed exception, and it's narrow for a specific reason: `onEach { _latestTask.value = it }` does exactly one thing — mirrors the upstream value, unconditionally, with no branching and no derived computation. It's the flow equivalent of assignment, not logic. Contrast with the anti-pattern in `singularity-todo-testable-vm` ("❌ Side effects inside combine"), where the side effect is *conditional* (`if (!draft.initialized) { _draft.value = seeded }`) and lives *inside* the `combine` lambda itself — meaning it silently re-runs, with branching logic, every time **any** upstream flow emits, including flows unrelated to what triggered the write.

**The rule, stated precisely:** an unconditional `onEach { cache.value = it }` immediately upstream of a `combine`, mirroring a single source with no branching, is fine — it's a plain cache, not derived state. A conditional or computed write *inside* `combine` (or `map`, `transform`, etc.) is not — that's business logic hiding in a place tests can't reach without subscribing to the whole downstream chain.

If your cache-population logic ever grows a condition (`if (...) _latestTask.value = ...`), that's the signal to stop and extract it into a named function called from `init {}`, exactly as `singularity-todo-testable-vm` describes for state initialization generally.

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

## Routing via Callbacks, NOT via Intent (Simple Navigation)

For **simple list→detail navigation** (no sheets, no dialogs), the cleanest pattern is **no routing intents at all**:

```kotlin
// ViewModel — NO routing intents in the sealed interface
sealed interface SavedAgendaListIntent {
    sealed interface Domain : SavedAgendaListIntent {
        data class Delete(val viewId: SavedAgendaViewId) : Domain
    }
}

// SavedAgendaListViewModel — only domain events (errors)
private val _events = MutableSharedFlow<SavedAgendaListEvent>(extraBufferCapacity = 4)
val events = _events.asSharedFlow()

// Screen — navigation via callback, NOT through VM
@Composable
fun SavedAgendaListScreen(...) {
    val navigator = LocalAgendaNavigator.current
    val viewModel: SavedAgendaListViewModel = koinViewModel()

    SavedAgendaListContent(
        state = state,
        onViewSelected = { viewId -> navigator.openSavedAgendaEdit(viewId) }, // ← direct callback
        onDelete = { viewId -> viewModel.onIntent(SavedAgendaListIntent.Domain.Delete(viewId)) },
        ...
    )

    NotificationHost(
        events = viewModel.events,
        mapper = { e -> when (e) { is SavedAgendaListEvent.ShowError -> Notification.Error(e.message) } },
    )
}
```

**When to use callbacks vs routing intents:**

| Case | Pattern |
|---|---|
| User taps a list item → detail | **Callback** `onViewSelected = { navigator.openSavedAgendaEdit(id) }` |
| User taps FAB → create screen | **Callback** `onCreateNew = { navigator.openCreate() }` |
| User opens a bottom sheet picker | **Routing intent** in sealed interface → `ActiveSheet` state on screen |
| User confirms a destructive action | **Domain intent** → VM calls repo → `ShowError`/`SaveSuccess` event |

**Rule**: If the navigation is a direct response to a user gesture with no intermediate state change, use a callback. If navigation depends on routing state (which sheet is open), use a routing intent.

**When a routing intent IS needed** — if you need to pass data from the VM to determine the destination (e.g., "open task detail" but the task ID comes from a loaded model):
```kotlin
// Still use callback — ID comes from the callback parameter, not from VM state
onViewSelected = { viewId -> navigator.openSavedAgendaEdit(viewId) }
```

**`extraBufferCapacity = 4` on domain events** — project convention for `MutableSharedFlow<*Event>` in ViewModels. See `singularity-todo-testable-vm` for the full explanation (why 4 vs 1 vs UNLIMITED, what actually goes wrong with `emit()` on rotation, and the distinction from `tryEmit()`).

Keep this value consistent across VMs unless you have a measured reason to deviate (a screen that legitimately produces >4 events/frame, e.g. an animation ticker). If you do deviate, document why next to the declaration.

---

## Reference Implementation

- `TaskEditorViewModel` — draft-editor pattern with pure `reduce()` + `TaskEditorReducerTest`
- `TaskDetailViewModel` — write-through pattern with single `onIntent()` + `mutate{}`; has sheet routing intents
- `ProjectDetailViewModel` — **minimal variant**: no sheets, only domain intents + navigation callbacks; demonstrates the pattern at its simplest
- `AgendaViewModel` — **evaluate-only variant**: `definition` injected at construction; watches all tasks via `TaskFilter.All`, evaluates in-process with `AgendaEvaluator.evaluate()`, emits `AgendaUiState`. No mutation intents — navigation only via `AgendaUiEvent.NavigateToTask`.
- `TaskDetailIntent.kt` — routing/domain separation in a real-world screen

## See Also

- **`singularity-todo-vm-pattern-overview`** — router: index to all VM skills
- `singularity-todo-task-callback-groups` — pairing this pattern with `@JvmInline value class Actions` in Composables
- `singularity-todo-ui-event-vs-state` — routing state (which sheet is open) is NOT a `SharedFlow` event
- `singularity-todo-testable-vm` — **testability pattern**: plain `MutableStateFlow`, scope injection, no `combine`+`stateIn`
- `singularity-todo-vm-koin-scoping` — how to register this VM in Koin (especially two-constructor testable VMs — never `viewModelOf`, always explicit `viewModel { ... }`)
- `singularity-todo-sheet-extraction` — routing intents for sheet navigation (e.g. `NavigateToChild`), `ActiveSheet` sealed interface, `*SheetsHost` composition, `CurrentContent` data class
- `docs/decisions/2026-09-09-task-detail-intent-refactor.md` — the ADR that formalized this pattern
- `docs/decisions/2026-09-09-project-detail-intent-refactor.md` — the minimal variant ADR (no sheets)
- `docs/decisions/2026-09-16-agenda-mr4-saved-views-create-reorder.md` — MR4 refactor that established the testable VM pattern

---

## Testability — Quick Reference

This pattern plays well with the testable VM pattern. The two are complementary:

| Concern | Pattern |
|---|---|
| Sealed Intent + onIntent dispatcher | `vm-intent-pattern` (this skill) |
| Plain `MutableStateFlow` + scope injection + `backgroundScope`/`Main.immediate` pitfalls | `singularity-todo-testable-vm` |

**When you write a VM with intents**, default to the testable shape — constructor, `Main.immediate` caveat, and the `backgroundScope` vs `this` distinction are all documented in `singularity-todo-testable-vm` → "Testable VM Template" and "Scope Injection Reference". Don't duplicate them here; one canonical source of truth.

---
name: singularity-todo-mvi-framework
description: Local MVI framework for Singularity Todo KMP. Covers MviViewModel base class, EventBus, MviIntent/MviEvent markers, IntentActions inline wrapper, DraftMviViewModel and DraftState helper. Use when migrating a ViewModel to the framework or when writing a new VM.
---

# MVI Framework — `core/ui/`

Minimal local MVI framework providing base classes and utilities for ViewModels.

## Files

| File | Purpose |
|---|---|
| `core/ui/Markers.kt` | `MviIntent`, `MviEvent` marker interfaces |
| `core/ui/MviViewModel.kt` | Full base: `EventBus` + `updateState` + `onIntent` |
| `core/ui/EventBus.kt` | Channel-backed one-shot event bus |
| `core/ui/IntentActions.kt` | `@JvmInline value class IntentActions<I : MviIntent>(dispatch: (I) → Unit)` |
| `core/ui/DraftState.kt` | Generic draft helper for editor VMs |
| `core/ui/DraftMviViewModel.kt` | Base for VMs that own a persisted draft |
| `core/ui/TestTags.kt` | Stable test tags for UI verification |

## Migration Guide

### Old → New pattern

**Before (hand-rolled MVI):**
```kotlin
class TagsViewModel(
    private val tagRepo: TagsRepository,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {
    private val _state = MutableStateFlow<TagsUiState>(TagsUiState.Loading)
    val state: StateFlow<TagsUiState> = _state.asStateFlow()
    private val _events = Channel<TagsUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        addCloseable(scope)
        scope.launch {
            tagRepo.observeAll().collect { tags ->
                _state.value = if (tags.isEmpty()) TagsUiState.Empty("") else TagsUiState.Content(tags)
            }
        }
    }

    fun delete(id: TagId) = scope.launch {
        tagRepo.delete(id).onFailure { _events.send(TagsUiEvent.ShowError(it.message ?: "Error")) }
    }
}
```

**After (MviViewModel):**
```kotlin
class TagsViewModel(
    private val tagRepo: TagsRepository,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<TagsUiState, TagsIntent, TagsUiEvent>(
    initialState = TagsUiState.Loading,
    scope = scope,
) {

    init {
        addCloseable(scope)
        scope.launch {
            tagRepo.observeAll()
                .catch { updateState { TagsUiState.Error(it.message ?: "Error") } }
                .collect { tags -> updateState {
                    if (tags.isEmpty()) TagsUiState.Empty("") else TagsUiState.Content(tags)
                } }
        }
    }

    override fun onIntent(intent: TagsIntent) {
        when (intent) {
            is TagsIntent.Delete -> scope.launch { delete(intent.id) }
        }
    }

    fun delete(id: TagId) = scope.launch {
        tagRepo.delete(id).onFailure { emit(TagsUiEvent.ShowError(it.message ?: "Error")) }
    }
}
```

### Adding Intent/Event sealed interfaces

Every migrated VM should add explicit `*Intent` and `*UiEvent` sealed interfaces:

```kotlin
sealed interface TagsIntent : MviIntent {
    data class Delete(val id: TagId) : TagsIntent
}

sealed interface TagsUiEvent : MviEvent {
    data class ShowError(val message: String) : TagsUiEvent
}
```

## API Reference

### `MviViewModel<S, I, E>`

```
Parameters:
  S   — UI state type (e.g. TagsUiState)
  I   — Intent type (e.g. TagsIntent) extending MviIntent
  E   — Event type (e.g. TagsUiEvent) extending MviEvent
```

**Properties:**
- `state: StateFlow<S>` — public read-only state
- `events: Flow<E>` — one-shot event flow (collect to handle events)

**Methods:**
- `emit(event: E)` — suspend emit a one-shot event
- `tryEmit(event: E): Boolean` — non-suspend emit (returns false if buffer full)
- `updateState(transform: (S) → S)` — update state with transform function
- `onIntent(intent: I)` — abstract; override to dispatch intents

### `IntentActions<I>`

Replaces per-feature `@JvmInline value class XxxActions` with a single generic type:

```kotlin
// Before
@JvmInline value class TagsActions(private val dispatch: (TagsIntent) -> Unit) { ... }

// After
val actions = IntentActions<TagsIntent>(viewModel::onIntent)
actions(TagsIntent.Delete(id))
```

### `DraftState<S>`

For editor VMs with local dirty state:

```kotlin
class TaskDraftState : DraftState<Task?>(null) {
    fun updateTitle(title: String) = update { it?.copy(title = title) }
    fun resetTo(task: Task) = reset(task)
}
```

## Intent method naming

**Always name the intent handler `onIntent`** — enforced by `IntentMethodName` detekt rule.

```kotlin
// ✅ Correct
override fun onIntent(intent: TagsIntent) { ... }

// ❌ Wrong
fun processIntent(intent: TagsIntent) { ... }
```

## Scope position

**`scope` must be the last constructor parameter** — enforced by `VmScopePosition` detekt rule.

## Init block

**Always call `addCloseable(scope)` in init** — enforced by `VmCloseable` detekt rule.

## `FeatureSlot` — splitting a god VM

A ViewModel that observes more than one repository should be a **coordinator plus slots**,
not one 500-line `when` over 30 intents. Each slice becomes a plain class:

```kotlin
interface FeatureSlot<S, I : MviIntent> {
    val state: StateFlow<S>   // StateFlow, not Flow — the coordinator reads it synchronously
    fun onIntent(intent: I)   // only this slot's intents
}
```

A slot is **not** a ViewModel: it is constructed by its coordinator, is not registered in
Koin, and has no lifecycle of its own — it receives the coordinator's `AutoCloseableCoroutineScope`.

The coordinator holds one slot per concern and merges them with `combineStates`:

```kotlin
combineStates(
    taskFlow, entity.state, draft.state, children.state,
    reminders.state, lifecycle.state, ai.state, backlinks.state,
) { task, entity, draft, children, reminders, lifecycle, ai, backlinks ->
    TaskDetailUi(...)   // one typed call, no intermediate Meta/Content data classes
}
```

`combineStates` is a type-safe `combine` for 2–8 flows. The two-to-five arities delegate to
`kotlinx.coroutines`; the wider ones confine the `Array<Any?>` cast to one private helper.
`kotlinx.coroutines` ships no typed overload past five, which is why the old code needed
`@Suppress("UNCHECKED_CAST")` or an intermediate data class per grouping level.

**The transform is non-suspending on purpose.** It cannot call a suspending repository write,
so the "side effect in a `combine`" bug is a compile error rather than a code review catch.

### Combine transforms must be pure

Enforced by the `NoCombineSideEffect` detekt rule — a `combine` transform may not contain
`.value =`, `seed()`, `Channel.send`, or `launchIn`. A `combine` re-runs its transform on
*every* upstream emission, so a write inside it re-fires and leaves stale state behind. Put
writes in a `collect { }` block:

```kotlin
// ❌ re-fires on every checklist/attachment update
combine(flowA, flowB) { a, b -> draftState.seed(a, b); State(a, b) }

// ✅ once per task change
.flatMapLatest { task -> draftState.seed(task.title); buildFlows(task) }
```

The check is AST-local, so a side effect hidden inside a callee is not reported.

## When to use a coordinator vs. a single VM

| Signal | Action |
|---|---|
| One repo observed, one concern | Single VM. Do not decompose. |
| >2 repo observations, >15 intents | Coordinator + slots |
| Two concerns share *no* state, no intent touches both | Consider splitting into separate screens entirely (`2026-09-09-notes-vm-split.md`) |
| Sibling VMs at screen level (`4× koinViewModel()`) | Avoid — N parallel subscriptions and the merge moves into the Composable. See `2026-09-26-pr24-rescope.md`. |

`SettingsContributor` is a separate, older abstraction with a different shape (`Flow` +
`observe()` + `suspend process`). It is not a `FeatureSlot`; do not force them together before
`SettingsViewModel` is migrated.

## Splitting a god VM — the reference case

`TaskDetailViewModel` (524 LOC, 30 intents, 18 deps) became `TaskDetailCoordinator` plus
seven slots. What to copy:

- **One intent marker per slot.** Each `XxxIntent.Domain` variant also implements a sealed
  marker (`TaskChildrenIntent`, …), and the slot's `onIntent` takes that marker. Passing the
  wrong intent is a compile error, not an unmatched branch.
- **The coordinator's `when` is exhaustive over the sealed intent**, so an unrouted variant
  also fails to compile.
- **Merge only what the screen renders.** The coordinator merged six flows, not nine —
  completion, lifecycle, and AI state do not appear in the UI state, so folding them in would
  recompute the whole screen on a delete for no visible change.
- **A read-only producer is not a slot.** `TaskBacklinksCollector` has no intent surface, so it
  is a plain class; an `onIntent` that ignores every argument advertises a mutation path that
  does not exist.
- **Seeding is not a projection.** `TaskDraftSlot.seed()` is public and idempotent, called from
  the task collector. A `combine` transform would re-run it on every child-collection update.
- **Slot tests construct one slot** with only the fakes it needs. See the retro caveat below.

**Test caveat (2026-09-28):** slot tests pump with real `delay()`, not `advanceUntilIdle()`.
The fakes' `FakeProfileAwareCurrentUser` runs on `Dispatchers.Default`, which virtual time
cannot advance. Three attempts to bind it to the test scheduler all failed — the fix is in
`FakeProfileAwareCurrentUser` and the user-scoped repository observers, not in the tests. Do
not re-derive this; read `2026-09-28-mr2-retro-findings.md` R6 first.

## See Also

- `singularity-todo-testable-vm` — VM test patterns
- `singularity-todo-feature-scaffold` — new VM scaffold template
- `singularity-todo-vm-migration-playbook` — step-by-step migration checklist
- `docs/decisions/2026-09-25-local-mvi-framework.md` — ADR
- `docs/decisions/2026-09-27-feature-slot-pattern.md` — FeatureSlot decision
- `docs/decisions/2026-09-27-framework-drift-resolution.md` — what the framework does *not* have (`StateStrategy.Atomic` is deferred)

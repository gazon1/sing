---
name: singularity-todo-mvi-framework
description: Local MVI framework for Singularity Todo KMP. Covers MviViewModel base class, EventBus/SharedEventBus, MviIntent/MviEvent markers, IntentActions inline wrapper, and DraftState helper. Use when migrating a ViewModel to the framework or when writing a new VM.
---

# MVI Framework — `core/ui/mvi/`

Minimal local MVI framework (~120 lines) providing base classes and utilities for ViewModels.

## Files

| File | Purpose |
|---|---|
| `core/ui/mvi/Markers.kt` | `MviIntent`, `MviEvent` marker interfaces |
| `core/ui/mvi/StatefulViewModel.kt` | Base with `MutableStateFlow` + `update()` |
| `core/ui/mvi/MviViewModel.kt` | Full base: `EventBus` + `updateState` + `onIntent` |
| `core/ui/mvi/EventBus.kt` | Channel-backed (`EventBus`) and SharedFlow-backed (`SharedEventBus`) one-shot event buses |
| `core/ui/mvi/StateStrategy.kt` | State update strategy (currently `Direct`) |
| `core/ui/mvi/DraftState.kt` | Generic draft helper for editor VMs |
| `core/ui/IntentActions.kt` | `@JvmInline value class IntentActions<I : MviIntent>(dispatch: (I) → Unit)` |

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

    private suspend fun delete(id: TagId) {
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
- `state: StateFlow<S>` — public read-only state (inherited from StatefulViewModel)
- `events: Flow<E>` — one-shot event flow (collect to handle events)

**Methods:**
- `emit(event: E)` — suspend emit a one-shot event (protected)
- `tryEmit(event: E): Boolean` — non-suspend emit (returns false if buffer full)
- `updateState(transform: (S) → S)` — suspend state update
- `onIntent(intent: I)` — abstract; override to dispatch intents

**Important: `scope` must be `private val` constructor parameter.** The MviViewModel init block calls `addCloseable(scope)`, so `scope` must be accessible as a class property. Always place `scope` last in the constructor.

```kotlin
// ✅ Correct — scope is private val, accessible in init
class MyViewModel(
    private val deps: MyDeps,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<MyState, MyIntent, MyEvent>(initialState = MyState.Loading, scope = scope) {
    init { addCloseable(scope) }
    // scope is accessible here
}

// ❌ Wrong — scope as bare parameter without storage
class MyViewModel(
    private val deps: MyDeps,
    scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),  // no private val!
) : MviViewModel<MyState, MyIntent, MyEvent>(initialState = MyState.Loading, scope = scope) {
    // ERROR: scope not accessible in init block
}
```

### `emit()` from non-suspend context

`emit()` is `protected suspend fun`. If you need to emit from a non-suspend callback (e.g. inside `fireAndForget`'s `onError` lambda), wrap it:

```kotlin
// ✅ Correct
is ProjectDetailIntent.Domain.ToggleTaskPin ->
    scope.fireAndForget(
        errorLabel = "Pin failed",
        onError = { e ->
            scope.launch { emit(ProjectDetailUiEvent.ShowError(...)) }
        },
    ) { taskRepo.togglePinned(intent.taskId) }

// ❌ Wrong — emit() called from non-coroutine context
is ProjectDetailIntent.Domain.ToggleTaskPin ->
    scope.fireAndForget(
        errorLabel = "Pin failed",
        onError = { e ->
            emit(ProjectDetailUiEvent.ShowError(...))  // COMPILE ERROR: emit is suspend
        },
    ) { taskRepo.togglePinned(intent.taskId) }
```

### `IntentActions<I>`

Replaces per-feature `@JvmInline value class XxxActions` with a single generic type:

```kotlin
// Before (per-feature Actions)
@JvmInline value class TagsActions(private val dispatch: (TagsIntent) -> Unit) {
    operator fun invoke(intent: TagsIntent) = dispatch(intent)
}
val actions = TagsActions(viewModel::processIntent)

// After (generic IntentActions)
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

## See Also

- `singularity-todo-testable-vm` — VM test patterns with MviViewModel
- `singularity-todo-feature-scaffold` — new VM scaffold template
- `singularity-todo-vm-migration-playbook` — step-by-step migration checklist
- `singularity-todo-vm-intent-pattern` — sealed Intent hierarchy pattern
- `docs/decisions/2026-09-25-local-mvi-framework.md` — ADR

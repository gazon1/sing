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

## See Also

- `singularity-todo-testable-vm` — VM test patterns
- `singularity-todo-feature-scaffold` — new VM scaffold template
- `singularity-todo-vm-migration-playbook` — step-by-step migration checklist
- `docs/decisions/2026-09-25-local-mvi-framework.md` — ADR

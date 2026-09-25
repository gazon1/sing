---
status: accepted
---
# Local MVI Framework — StatefulViewModel + MviViewModel + EventBus

## Context

Across 25 ViewModels the project had 4 categories of boilerplate duplication:

1. **Own `_state: MutableStateFlow` + `state: StateFlow`** — repeated in every VM
2. **Own event channel** — `Channel<UiEvent>` or `MutableSharedFlow<UiEvent>` scattered across 24 VMs
3. **`?: return` guards** — `when` branches checking `state is Content` before accessing content-specific fields
4. **Duplicate `XxxActions`** classes — `TagsActions`, `NotesActions`, `TaskActions`, `ProjectActions` each wrapping `(I) → Unit`

Meanwhile, a `stateIn(WhileSubscribed(5000, 0))` workaround was used in 10+ VMs to avoid backstack leaks from `stateIn`, creating subtle `Npe` risk on cold starts.

## Idea

Introduce a minimal (~120 lines) local MVI framework in `core/ui/mvi/` with three base classes:

- `StatefulViewModel<S>` — owns `__state: MutableStateFlow` + `state: StateFlow` + `update()`
- `MviViewModel<S, I : MviIntent, E : MviEvent>` — adds `EventBus<E>`, typed `emit()`, `updateState()`, and abstract `onIntent(I)`
- `EventBus<E : MviEvent>` — `Channel`-backed, capacity `Channel.BUFFERED`, `receiveAsFlow()`

Plus two opt-in utilities:

- `StateStrategy.Atomic` — `ReentrantMutex`-based SST for VMs with real concurrent read-modify-write
- `updateState<reified T>()` — smart-cast helper eliminating `?: return` guards
- `IntentActions<I : MviIntent>` — generic `@JvmInline value class` replacing per-feature `XxxActions`

## Decision

### 1. Marker interfaces (`core/ui/mvi/Markers.kt`)

```kotlin
interface MviIntent
interface MviEvent
```

All feature `XxxIntent : MviIntent` and `XxxUiEvent : MviEvent` — compile-time sealed hierarchies only, no runtime checks.

### 2. EventBus (`core/ui/mvi/EventBus.kt`)

```kotlin
class EventBus<E : MviEvent>(capacity: Int = Channel.BUFFERED) {
    private val _channel = Channel<E>(capacity)
    val flow: Flow<E> = _channel.receiveAsFlow()
    suspend fun emit(event: E) = _channel.send(event)
    fun tryEmit(event: E): Boolean = _channel.trySend(event).isSuccess
}
```

`Channel.BUFFERED` (64 elements) covers all one-shot UI events. `SharedEventBus<E>` (MutableSharedFlow-backed) available for broadcast scenarios but not yet used.

### 3. StatefulViewModel (`core/ui/mvi/StatefulViewModel.kt`)

```kotlin
abstract class StatefulViewModel<S>(
    initialState: S,
    scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {
    init { addCloseable(scope) }
    @Suppress("VariableNaming", "BackingPropertyNaming")
    protected val __state = MutableStateFlow(initialState)
    val state: StateFlow<S> = __state.asStateFlow()
    protected fun update(reducer: (S) -> S) { __state.update(reducer) }
}
```

`__state` (triple-underscore) satisfies ktlint `VariableNaming` and `BackingPropertyNaming` rules without suppressing individual occurrences. `scope` is always last in constructors.

### 4. MviViewModel (`core/ui/mvi/MviViewModel.kt`)

```kotlin
abstract class MviViewModel<S, I : MviIntent, E : MviEvent>(
    initialState: S,
    extraEventCapacity: Int = Channel.BUFFERED,
    private val stateStrategy: StateStrategy = StateStrategy.Direct,
    scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : StatefulViewModel<S>(initialState, scope) {

    private val _events: EventBus<E> = EventBus(extraEventCapacity)
    val events: Flow<E> = _events.flow
    protected suspend fun emit(event: E) = _events.emit(event)
    protected fun tryEmit(event: E): Boolean = _events.tryEmit(event)

    private val mutex = if (stateStrategy == StateStrategy.Atomic) ReentrantMutex() else null

    protected suspend fun updateState(transform: suspend S.() -> S) {
        when (stateStrategy) {
            StateStrategy.Direct -> __state.update { transform(it) }
            StateStrategy.Atomic -> mutex!!.withReentrantLock { __state.update { transform(it) } }
        }
    }

    protected suspend inline fun <reified T : S> updateState(
        noinline transform: suspend T.() -> T
    ) {
        updateState { if (this is T) transform() else this }
    }

    abstract fun onIntent(intent: I)
}
```

`stateStrategy` default `Direct` — no overhead for VMs without concurrent mutations. `Atomic` opt-in only for VMs with real race conditions (TaskCreateViewModel, ProjectDetailViewModel).

### 5. StateStrategy (`core/ui/mvi/StateStrategy.kt`)

```kotlin
sealed interface StateStrategy {
    data object Direct : StateStrategy
    data object Atomic : StateStrategy
}
```

`Atomic` wraps `MutableStateFlow.update` in `ReentrantMutex.withReentrantLock`. ~15x slower than Direct but safe for compare-and-set patterns.

### 6. IntentActions (`core/ui/IntentActions.kt`)

```kotlin
@JvmInline
value class IntentActions<I : MviIntent>(private val dispatch: (I) -> Unit) {
    operator fun invoke(intent: I) = dispatch(intent)
    // Convenience call-site syntax: actions.onDelete(id) → dispatch(NotesIntent.Delete(id))
}
```

Per-feature `XxxActions` replaced by `IntentActions<XxxIntent>`. Usage: `val actions = remember(viewModel) { IntentActions(viewModel::onIntent) }`.

### 7. Detekt rules (warn mode, MR-0 → error mode MR-4)

| Rule | What it catches |
|---|---|
| `MviViewModelExtRule` | `*ViewModel.kt` has `_state: MutableStateFlow` + event channel but does NOT extend `MviViewModel` |
| `IntentMethodNameRule` | VM has a method processing sealed Intent that is NOT named `onIntent` |
| `VmScopePositionRule` | `scope: AutoCloseableCoroutineScope` is not the last constructor parameter |
| `VmCloseableRule` | VM has `scope` field but `init {}` does NOT call `addCloseable(scope)` |

All rules in `detekt-rules/` module, warn-only in MR-0, error in MR-4 after migration complete.

## Rationale

- **~120 lines** beats 6+ external libraries (Orbit, FlowMVI, MVIKotlin) for this project's scale
- **Channel over SharedFlow** for events: single consumer (NotificationHost) makes buffered Channel ideal; avoids SharedFlow's "event dropped if no subscriber at emission" problem
- **`__state` triple-underscore**: ktlint's `VariableNaming` fires on `_state` and `BackingPropertyNaming` fires on the `state` backing property — the only clean solution is a name ktlint doesn't flag
- **`StateStrategy.Atomic` opt-in**: overhead is real (~15x) and only justified for VMs with actual concurrent mutations (TaskCreate `compareAndSet`, ProjectDetail concurrent edits)
- **`updateState<reified T>`**: eliminates `filterIsInstance<T>().first()` / `?: return` boilerplate in sealed-state VMs without runtime overhead
- **`IntentActions<I>`**: `@JvmInline value class` means zero allocation at call sites — identical to hand-rolled per-feature version at runtime

## Consequences

- 25 VMs migrated across 3 MRs (MR-0 PoC + MR-1 simple VMs + MR-2 editor VMs + MR-3 complex VMs)
- `TagsViewModel`, `AccountSettingsViewModel`, `StatisticsViewModel`, `ArchiveViewModel`, `AttachmentsViewModel`, `AiUsageViewModel` in MR-1
- `ProjectEditorViewModel`, `TaskCreateViewModel`, `NotesListViewModel` in MR-2
- `TaskDetailViewModel`, `SavedAgendaViewModel`, `CalendarViewModel`, `SearchViewModel`, `ProfileSwitcherViewModel`, `AuthViewModel`, `BackupViewModel` in MR-3
- `AgendaViewModel` and `ChatViewModel` excluded — use `combine + stateIn(WhileSubscribed)` pattern already validated; detekt skip by name
- Coverage target: 100% for `StatefulViewModel`, `MviViewModel`, `EventBus`, `StateStrategy`, `DraftState`
- All migrations use `scope: AutoCloseableCoroutineScope` as last constructor parameter with secondary no-arg Koin constructor
- `core/ui/state/StateFlowExt.kt::updateState` removed after all migrations complete (MR-4)
- 4 detekt rules promoted from warn to error in MR-4

## Links

- `docs/decisions/DIGEST.md`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/mvi/`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/IntentActions.kt`
- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/rules/`
- `shared/src/commonTest/kotlin/com/singularity/todo/core/ui/mvi/`
- MR-0: `refactor(local-mvi-framework): introduce MviViewModel base + PoC on TagsViewModel`
- MR-1: `refactor(mvi): migrate simple VMs (AccountSettings, Statistics, Archive, Attachments, AiUsage)`
- MR-2: `refactor(mvi): migrate editor VMs + IntentActions`
- MR-3: `refactor(mvi): migrate complex VMs with concurrent state`

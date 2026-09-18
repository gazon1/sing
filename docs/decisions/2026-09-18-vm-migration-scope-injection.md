# ADR: ViewModel scope injection — canonical 4-arg constructor pattern

## Context

13 ViewModels in the codebase directly referenced `viewModelScope`, making them untestable in pure JVM context (no `viewModelScope` outside Android). Additionally, several VMs had duplicate boilerplate for draft state management, debounce, and base class patterns.

## Decision

### Canonical VM constructor pattern

Every new or migrated ViewModel follows this pattern:

```kotlin
class MyVM(
    private val dep1: Dep1,
    private val dep2: Dep2,
    private val scope: CoroutineScope,  // TESTABLE: injected, not viewModelScope
    sharingStarted: () -> SharingStarted = { SharingStarted.WhileSubscribed(5000) },
) : ViewModel() {

    /** Production constructor — Koin uses this. */
    constructor(dep1: Dep1, dep2: Dep2) : this(
        dep1, dep2,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    val state = someFlow.stateIn(scope, sharingStarted(), initial)
}
```

### BaseViewModel<T>

Optional base class in `core/ui/viewmodel/BaseViewModel.kt`:

```kotlin
abstract class BaseViewModel<S, I, E>(
    initialState: S,
    scope: CoroutineScope,
) : ViewModel() {
    protected val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()
    private val _events = MutableSharedFlow<E>(extraBufferCapacity = 4)
    val events: SharedFlow<E> = _events.asSharedFlow()
    protected suspend fun emit(event: E) = _events.emit(event)
}
```

### DraftState<T>

Generic draft state in `core/ui/draft/DraftState.kt`:

```kotlin
abstract class DraftState<T : Any>(initial: T) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<T> = _state.asStateFlow()
    val current: T get() = _state.value
    protected fun seed(current: T, initialized: T.() -> Boolean)
    protected fun update(transform: T.() -> T) {
        _state.value = _state.value.transform()
    }
}
```

Concrete VMs extend this:

```kotlin
class ProjectDetailDraftState : DraftState<ProjectDetailDraft>(ProjectDetailDraft.empty()) {
    fun setName(name: String) = update { it.copy(name = name) }
    fun setDescription(desc: String) = update { it.copy(description = desc) }
}
```

### Filter<T>, Sort<T>

Marker interfaces in `core/domain/Filter.kt` for composable list filtering/sorting.

### Tier C helpers

- `ClockExt.kt`: `daysUntil(target: LocalDate): Int`
- `Validation.kt`: `requireNotBlank`, `requireMaxLength`

## Rationale

1. **`scope: CoroutineScope` as constructor param** — enables testing with `TestScope.backgroundScope`; production uses `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`

2. **`sharingStarted` as lambda** — avoids `WhileSubscribed` proliferation; test VMs can pass `{ SharingStarted.Eagerly }` to start collection immediately

3. **`viewModelOf` → `viewModel { }` in Koin** — explicit constructor call required when multiple constructors exist; disambiguates primary (testable, with scope) from secondary (production, auto-scope)

4. **`DraftState<T>` base class** — eliminates duplicate `MutableStateFlow`, `seed()`, and `update()` logic across TaskDetail and ProjectDetail VMs

## Consequences

- All 13 migrated VMs are now testable with `backgroundScope` injection
- Existing `viewModelOf` calls in DI modules updated to `viewModel { Vm(...) }` form
- Test classes updated: `createVm()` now takes `scope = backgroundScope` via `TestScope.createVm()`
- `Dispatchers.Main.immediate` in secondary constructors causes `IllegalStateException` on JVM — tests must use the primary constructor with `backgroundScope`
- Phase 8 (test rewrites) and Phase 9 (verification) follow from this migration

## Links

- `docs/decisions/2026-09-18-testing-best-practices.md` — testing philosophy
- `docs/decisions/2026-09-06-koin-vm-viewmodelof-koinviewmodel.md` — Koin VM scoping
- `docs/decisions/2026-09-05-koin-suspend-bridge.md` — coroutine bridge rationale

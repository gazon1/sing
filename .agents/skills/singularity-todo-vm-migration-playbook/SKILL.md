---
name: singularity-todo-vm-migration-playbook
description: Step-by-step playbook for migrating an existing ViewModel from stateIn/combine/scopeOverride to the canonical scope-as-default-param pattern. Use when retrofitting an old VM, or when writing a new VM. Covers detection grep, the 5-step migration, side-effect extraction, Koin registration, test patterns, and pitfalls.
---

# VM Migration Playbook (2026-09-23)

The project's canonical ViewModel pattern uses **`AutoCloseableCoroutineScope` with a default param** in the primary constructor — **no secondary constructor, no `scopeOverride`**.

## When to use this playbook

- The VM has `scopeOverride: CoroutineScope? = null` + a broken getter
- The VM has a **secondary constructor** that delegates with `scope = AutoCloseableCoroutineScope()` (superseded canonical)
- The VM uses `viewModelScope.launch { ... }` directly
- The VM uses `stateIn(WhileSubscribed(5000))` with complex init logic
- You're writing a **new** VM and want to follow the canonical shape

## Canonical target shape (MviViewModel)

```kotlin
sealed interface MyIntent : MviIntent { ... }
sealed interface MyEvent : MviEvent { ... }

class MyViewModel(
    private val deps: MyDeps,
    private val mode: MyScreenMode,
    // ... all non-defaulted dependencies ...
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<MyUiState, MyIntent, MyEvent>(
    initialState = MyUiState.Loading,
    crashReporter = crashReporter,
    scope = scope,
) {

    // No addCloseable(scope): MviViewModel's initializer already registers the scope it
    // was given. A second call gives one resource two owners; `VmCloseable` reports it.

    init {
        scope.launch { /* populate state via updateState */ }
    }

    override fun onIntent(intent: MyIntent) {
        when (intent) { ... }
    }
}
```

Key properties:

1. **`scope` is LAST** — `VmScopePosition` detekt rule
2. **No manual `_state` or `_events`** — MviViewModel provides them
3. **`emit()` is suspend, `updateState()` is not** — `emit` needs a coroutine, `updateState` does not
4. **`onIntent` is the dispatcher** — `IntentMethodName` detekt rule enforces this
5. **No `addCloseable(scope)`** — the base class registers that scope. See step 3.
6. **`scope` has no no-arg default** — `AutoCloseableCoroutineScope` takes a
   `CoroutineContext`. Production writes `reportingScope(crashReporter)`; a test passes
   `testScope(this)`. `singularity-todo-testable-vm` has the contract.
7. **`sharingStarted: () -> SharingStarted` — remove entirely** (was only needed for `stateIn`).
8. **DI binding uses explicit `viewModel { MyViewModel(...) }`** — never `viewModelOf` for VMs with complex ctors.

---

## Detection commands

| Pattern | Command |
|---|---|
| scopeOverride getter | `grep -rn "scopeOverride: CoroutineScope? = null" shared/src/commonMain/` |
| processIntent (→ onIntent) | `grep -rln "fun processIntent" shared/src/commonMain/.../viewmodel/` |
| Hand-rolled event channel | `grep -rln "Channel<.*UiEvent>" shared/src/commonMain/.../viewmodel/` |
| viewModelScope.launch | `grep -rn "viewModelScope.launch" shared/src/commonMain/.../viewmodel/` |
| stateIn usages | `grep -rn "\.stateIn(" shared/src/commonMain/.../viewmodel/` |
| sharingStarted param | `grep -rn "sharingStarted" shared/src/commonMain/.../viewmodel/` |
| Non-MviViewModel VMs | `grep -rln ": ViewModel()" shared/src/commonMain/.../viewmodel/` |

---

## MviViewModel Migration (hand-rolled → MviViewModel)

Use this when the VM has hand-rolled MVI boilerplate: `_state`, `_events`, `Channel`, `MutableSharedFlow`, etc.

### Step A: Add Intent + Event sealed interfaces

```kotlin
sealed interface MyIntent : MviIntent {
    // Copy all intents from the sealed interface
    data class Delete(val id: MyId) : MyIntent
}

sealed interface MyEvent : MviEvent {
    data class ShowError(val message: String) : MyEvent
}
```

### Step B: Extend MviViewModel

```kotlin
// Before
class MyViewModel(
    private val deps: MyDeps,
    private val scope: AutoCloseableCoroutineScope,  // a plain ViewModel registers it itself
) : ViewModel() {
    init { addCloseable(scope) }
    private val _state = MutableStateFlow<MyUiState>(MyUiState.Loading)
    val state: StateFlow<MyUiState> = _state.asStateFlow()
    private val _events = Channel<MyEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

// After
class MyViewModel(
    private val deps: MyDeps,
    crashReporter: CrashReportingPort = NoOpCrashReportingPort(),
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),
) : MviViewModel<MyUiState, MyIntent, MyEvent>(
    initialState = MyUiState.Loading,
    crashReporter = crashReporter,
    scope = scope,
) {
    // no addCloseable(scope) — the base initializer already registered it
    // state and events are inherited from MviViewModel
```

### Step C: Replace `_events.send()` with `emit()`

```kotlin
// Before
_events.send(MyEvent.ShowError("Error"))

// After
emit(MyEvent.ShowError("Error"))
```

**Remember:** `emit()` is `protected suspend fun`. If the failure belongs to a one-shot event, use `emitError(errorLabel, SomeUiEvent::ShowError) { repoCall() }` from the base class — its routing lambda is `suspend`, so there is no nested `scope.launch { emit(...) }`.

### Step D: Replace `_state.value = ...` with `updateState { }` or direct assignment

```kotlin
// Before
_state.value = MyUiState.Content(items)

// After
updateState { MyUiState.Content(items) }
// or directly (sync):
_state.value = MyUiState.Content(items)
```

### Step E: Rename `processIntent` → `onIntent`

```kotlin
// Before
fun processIntent(intent: MyIntent) = scope.launch { ... }

// After
override fun onIntent(intent: MyIntent) {
    when (intent) {
        is MyIntent.Delete -> scope.launch { ... }
    }
}
```

### Step F: Add Intent/Event to type parameters

```kotlin
class MyViewModel(...) : MviViewModel<MyUiState, MyIntent, MyEvent>(...)
```

---

## Standard Migration — 5 Steps

### Step 1: Identify all constructor parameters

Read the full constructor. Note which are:

- Dependencies (repos, use cases, etc.)
- Runtime values (IDs, modes, etc.)
- The old `scopeOverride: CoroutineScope? = null` or `scope: AutoCloseableCoroutineScope`
- `sharingStarted: () -> SharingStarted` — **remove this entirely** (only needed for `stateIn`)

### Step 2: Move `scope` to last param with default, remove `scopeOverride`/`sharingStarted`

**Before:**

```kotlin
class FooViewModel(
    private val deps: FooDeps,
    param1: Type1,
    private val scopeOverride: CoroutineScope? = null,    // OLD
    sharingStarted: () => SharingStarted = { SharingStarted.WhileSubscribed(5000) },  // OLD
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope  // BROKEN GETTER
```

**After:**

```kotlin
class FooViewModel(
    private val deps: FooDeps,
    param1: Type1,
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),  // NEW
) : ViewModel() {
    // no getter — scope is a plain constructor parameter
```

### Step 3: Delete secondary constructor (if present); register the scope once

**Before:**

```kotlin
class FooViewModel(
    private val deps: FooDeps,
    param1: Type1,
    private val scope: AutoCloseableCoroutineScope,  // non-defaulted
) : ViewModel() {
    constructor(deps: FooDeps, param1: Type1) : this(
        deps, param1, reportingScope(NoOpCrashReportingPort()),
    )
```

**After (plain `androidx.lifecycle.ViewModel` — it needs the registration):**

```kotlin
class FooViewModel(
    private val deps: FooDeps,
    param1: Type1,
    scope: AutoCloseableCoroutineScope = reportingScope(crashReporter),  // default added
) : ViewModel() {
    init {
        addCloseable(scope)  // Tier-1 cleanup
    }
    // no secondary constructor
```

**But if the class extends `MviViewModel`/`DraftMviViewModel`, stop here:** the base
initializer already registers that scope, so the `init { addCloseable(scope) }` above is the
duplicate the `VmCloseable` rule reports. Delete the secondary constructor and the default
parameter's bare `AutoCloseableCoroutineScope()` — nothing else. Registering a *different*
closeable (a subprocess, a file handle) is still correct.

### Step 4: Extract side-effects from `combine`/`flatMapLatest`

If the VM has `_state.value = ...` or `_cache.value = ...` inside a `combine`/`flatMapLatest` lambda, extract it to a **dedicated collector**:

**Before (TaskDetailViewModel):**

```kotlin
init {
    scope.launch {
        combine(
            taskId.flatMapLatest { deps.taskRepo.observe(it) }.filterNotNull(),
            titleEdits.debounce(debounceMs.milliseconds),
        ) { task, title ->
            _latestTask.value = task   // ← SIDE EFFECT inside combine
            task to title
        }.collect { (task, title) ->
            deps.updateTask(task.copy(title = title))
        }
    }
}
```

**After:**

```kotlin
init {
    // Dedicated collector: populate _latestTask cache FIRST
    scope.launch {
        taskId.flatMapLatest { deps.taskRepo.observe(it) }
            .filterNotNull()
            .collect { _latestTask.value = it }
    }
    // State chain reads from cache
    scope.launch {
        combine(
            _latestTask.filterNotNull(),
            titleEdits.debounce(debounceMs.milliseconds),
        ) { task, title -> task to title }
            .collect { (task, title) ->
                deps.updateTask(task.copy(title = title))
                    .onFailure { emitError("Save failed") }
            }
    }
}
```

**Same pattern for ProjectDetailViewModel:**

```kotlin
// Before: _latestProject.value = project inside combine
// After:
scope.launch {
    projectFlow.collect { _latestProject.value = it }
}
scope.launch {
    combine(/* reads from _latestProject */).collect { /* no side effect */ }
}
```

### Step 5: Verify DI module registration

For VMs without runtime params — use explicit `viewModel { }` lambda:

```kotlin
// BEFORE (ambiguous with scope/sharingStarted params)
viewModelOf(::FooViewModel)

// AFTER (always explicit — Kotlin compiler validates arg assignment)
viewModel { FooViewModel(deps = get(), param1 = get()) }
```

For VMs with runtime params:

```kotlin
viewModel { (projectId: ProjectId) ->
    ProjectDetailViewModel(
        projectId = projectId,
        projectRepo = get(),
    )
}
```

**Why `viewModelOf` doesn't work with complex ctors**: Koin's reflection-based DSL cannot disambiguate a `CoroutineScope` bean from a `() -> SharingStarted` parameter when both are in the ctor. Explicit `viewModel { }` lambda forces Kotlin's compiler to validate parameter assignment.

**Why NOT `factory {}`**: factory creates a new instance per `get()` call — no lifecycle scoping, state lost on navigation, memory leaks. Always `viewModel { }`.

---

## Common Pitfalls

### Pitfall 1: Forgetting to remove the broken getter

```kotlin
// WRONG — still has broken getter
private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope  // ← remove this

// CORRECT — no getter, scope is plain val
private val scope: AutoCloseableCoroutineScope  // ← plain val, no getter
```

### Pitfall 2: `sharingStarted` parameter left in class

If the VM had `sharingStarted: () -> SharingStarted`, **remove it entirely** after migrating away from `stateIn`. Tests that pass `sharingStarted = { SharingStarted.Eagerly }` must be updated to stop passing it.

### Pitfall 3: State initialization order

When migrating from `stateIn(scope, WhileSubscribed, initialValue)` to `MutableStateFlow(initialValue)`, make sure state is initialized **before** collectors start:

```kotlin
// CORRECT order
private val _state = MutableStateFlow<FooUiState>(FooUiState.Loading)  // 1. init first
val state: StateFlow<FooUiState> = _state.asStateFlow()

init {
    // 2. collectors start after; no addCloseable(scope) on an MVI subclass
    scope.launch { }
}
```

---

## Migration Order (multi-VM MR)

1. **Simple VMs first**: add the `scope` default (a plain `ViewModel` also adds `init { addCloseable(scope) }`; an MVI subclass does not)
2. **Standard broken VMs next**: replace scopeOverride getter pattern → scope default param
3. **Side-effects-in-combine VMs last**: extract side-effects first, then migrate
4. Run tests: `./gradlew :shared:jvmTest --no-daemon`

If `UncompletedCoroutinesError` → side-effect still inside combine or missing scope injection.

---

## See Also

- `singularity-todo-testable-vm` — canonical VM shape + IntentActions + BAN list
- `singularity-todo-test-helpers` — standard test helpers for migrated VMs
- `singularity-todo-koin-dsl` — Koin 4.x DSL: `viewModelOf` vs `viewModel {}`
- `singularity-todo-coroutine-scopes` — scope placement and anti-patterns

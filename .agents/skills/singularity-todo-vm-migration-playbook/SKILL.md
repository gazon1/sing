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

## Canonical target shape

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class MyViewModel(
    private val deps: MyDeps,
    private val mode: MyScreenMode,
    // ... all non-defaulted dependencies ...
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {

    init {
        addCloseable(scope)  // Tier-1 cleanup: cancel scope when VM cleared
    }

    private val _state = MutableStateFlow<MyUiState>(MyUiState.Loading)
    val state: StateFlow<MyUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<MyEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    init {
        scope.launch { /* populate _state */ }
    }
}
```

Key properties:
1. **`scope` is LAST** in the primary ctor — after all non-defaulted deps.
2. **Default param = `AutoCloseableCoroutineScope()`** — no secondary ctor needed.
3. **`init { addCloseable(scope) }`** registers cleanup.
4. **`sharingStarted: () -> SharingStarted` — remove entirely** (was only needed for `stateIn`).
5. **DI binding uses explicit `viewModel { MyViewModel(...) }`** — never `viewModelOf` for VMs with complex ctors.

---

## Detection commands

```bash
# Old broken pattern — scopeOverride getter
grep -rn "scopeOverride: CoroutineScope? = null" shared/src/commonMain/

# Old canonical pattern — secondary constructor
grep -rn "AutoCloseableCoroutineScope()" shared/src/commonMain/

# Direct viewModelScope usage (should use injected scope instead)
grep -rn "viewModelScope.launch" shared/src/commonMain/kotlin/com/singularity/todo/feature/*/presentation/viewmodel/

# stateIn usages (remaining ones after migration = legitimate exceptions)
grep -rn "\.stateIn(" shared/src/commonMain/kotlin/com/singularity/todo/feature/*/presentation/viewmodel/

# Side-effects inside combine (look for .value = assignments inside combine/flatMapLatest lambdas)
grep -rn "\.value\s*=" shared/src/commonMain/kotlin/com/singularity/todo/feature/*/presentation/viewmodel/ | \
  grep -v " //\|@\|override\|private val _\|MutableStateFlow\|StateFlow\|SharedFlow"

# sharingStarted parameter (should be removed from VMs without stateIn)
grep -rn "sharingStarted" shared/src/commonMain/kotlin/com/singularity/todo/feature/*/presentation/viewmodel/
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
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),  // NEW
) : ViewModel() {
    // no getter — scope is plain val
```

### Step 3: Delete secondary constructor (if present), add `init { addCloseable(scope) }`

**Before:**
```kotlin
class FooViewModel(
    private val deps: FooDeps,
    param1: Type1,
    private val scope: AutoCloseableCoroutineScope,  // non-defaulted
) : ViewModel() {
    constructor(deps: FooDeps, param1: Type1) : this(
        deps, param1, AutoCloseableCoroutineScope(),
    )
```

**After:**
```kotlin
class FooViewModel(
    private val deps: FooDeps,
    param1: Type1,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),  // default added
) : ViewModel() {
    init {
        addCloseable(scope)  // Tier-1 cleanup
    }
    // no secondary constructor
```

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
    addCloseable(scope)
    scope.launch { /* 2. collectors start after */ }
}
```

### Pitfall 4: Smart-cast failure on class properties

```kotlin
// BROKEN — smart cast fails because mode is a class property
init {
    scope.launch {
        when (mode) {
            is ScreenMode.Edit -> initEditMode()  // mode narrowed here...
        }
    }
}
private suspend fun initEditMode() {
    val id = mode.viewId  // ERROR: mode is back to declared type
}

// FIX — pass narrowed local val to function
init {
    scope.launch {
        when (val m = mode) {  // m is a local val, smart-cast survives
            is ScreenMode.Edit -> initEditMode(m)
        }
    }
}
```

### Pitfall 5: Stale closures on `_state`

```kotlin
// WRONG — `view` captured from outside is stale
private fun emitEditingState(view: SavedAgendaView? = null) {
    _state.value = Editing(view ?: ..., draft, ...)
}

// CORRECT — read current state inside the function
private fun emitEditingState() {
    val current = _state.value
    _state.value = Editing(
        view = (current as? SavedAgendaViewState.Editing)?.view,
        draft = draftState.current,
        ...
    )
}
```

---

## Side-Effects-in-Combine Fix (advanced)

### Symptom

After migrating the VM constructor and writing tests that collect `vm.state.value`, tests fail with:

```
kotlinx.coroutines.test.UncompletedCoroutinesError: After waiting for 1m,
there were active child jobs
```

**OR** tests hang indefinitely past their 60s timeout.

### Why

The repository-owned scope runs its `stateIn` collector on `Dispatchers.Default` — a real thread pool outside the test dispatcher's `TestScheduler`. `advanceUntilIdle()` does not advance virtual time on `Default`. The upstream feed never emits within the test → state never updates → `UncompletedCoroutinesError`.

### Fix: dedicated collector for upstream cache

See `singularity-todo-testable-vm` for the full pattern. The short version:

```kotlin
// Populate cache FIRST via dedicated collector (runs before combine chain)
scope.launch {
    taskId.flatMapLatest { deps.taskRepo.observe(it) }
        .filterNotNull()
        .collect { _latestTask.value = it }
}

// THEN read from cache in combine/debounce chain
scope.launch {
    combine(
        _latestTask.filterNotNull(),
        titleEdits.debounce(debounceMs.milliseconds),
    ) { task, title -> task to title }
        .collect { (task, title) ->
            deps.updateTask(task.copy(title = title))
        }
}
```

---

## Migration Order (for a multi-VM MR)

When migrating many VMs in a single MR:

1. **Use this playbook** for each VM
2. **Migration sequence** — group by complexity:
   - Simple VMs first (no scopeOverride, just add `scope` default + `init { addCloseable }`)
   - Standard broken VMs next (replace scopeOverride getter pattern)
   - Side-effects-in-combine VMs last (extract side-effects first, then migrate)
3. After migration, run tests: `./gradlew :shared:jvmTest --no-daemon`
4. If `UncompletedCoroutinesError` → missing scope injection or side-effect still inside combine

---

## See Also

- `singularity-todo-testable-vm` — canonical VM shape + DraftState pattern + BAN list
- `singularity-todo-test-helpers` — standard test helpers for migrated VMs
- `singularity-todo-koin-dsl` — Koin 4.x DSL: `viewModelOf` vs `viewModel {}` vs `factory`
- `singularity-todo-coroutine-scopes` — full pattern for scope placement, anti-pattern, lifecycle
- `docs/decisions/2026-09-23-test-standards-enforcement.md` — full ADR documenting all 9 PRs
- `docs/decisions/2026-09-18-testing-best-practices.md` — testing principles

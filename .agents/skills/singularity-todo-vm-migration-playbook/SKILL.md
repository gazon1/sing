---
name: singularity-todo-vm-migration-playbook
description: Step-by-step playbook for migrating an existing ViewModel to the canonical scope-as-default-param pattern (AutoCloseableCoroutineScope, primary-ctor default, no secondary ctor). Use when retrofitting an old VM with scopeOverride / secondary ctor / viewModelScope direct usage, or when writing a new VM in this project. Covers detection grep, the 4-step migration, Koin registration (`viewModel { }` not `viewModelOf`), test patterns, and pitfalls.
---

# VM Migration Playbook (current canonical)

The project's canonical ViewModel pattern (as of 2026-09-21) uses **`AutoCloseableCoroutineScope` with a default param** in the primary constructor — **no secondary constructor**. This playbook documents how to migrate an existing VM to that shape.

## When to use this playbook

- The VM has `scopeOverride: CoroutineScope? = null` + a broken getter
- The VM has a **secondary constructor** that delegates with `scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` (the previous canonical shape — now superseded)
- The VM uses `viewModelScope.launch { ... }` directly
- You're writing a **new** VM and want to follow the canonical shape

## Canonical target shape

```kotlin
class MyViewModel(
    private val deps: MyDeps,
    private val param1: Type1,
    // ... all non-defaulted dependencies ...
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {

    init {
        addCloseable(scope)  // Tier-1 cleanup: cancel scope when VM cleared
    }

    // No secondary constructor — the default param handles Koin production binding
    // and tests pass `scope = testScope(...)` explicitly when needed
}
```

Key properties:
1. **`scope` is LAST** in the primary ctor — after all non-defaulted deps, but `sharingStarted: () -> SharingStarted` (if present) goes AFTER `scope`.
2. **Default param** = `AutoCloseableCoroutineScope()` — no secondary ctor needed.
3. **`init { addCloseable(scope) }`** registers cleanup. `AutoCloseableCoroutineScope` cancels on `close()`, so this is the entire Tier-1 cleanup.
4. **DI binding** uses **explicit `viewModel { MyViewModel(get(), get(), ...) }`** — never `viewModelOf(::MyViewModel)` (constructor ambiguity: Koin can't disambiguate from `() -> SharingStarted` or `CoroutineScope` types).

## Detection commands

```bash
# Old broken pattern — has both scopeOverride AND viewModelScope direct usage
grep -rn "scopeOverride: CoroutineScope? = null" shared/src/commonMain/

# Old canonical pattern — secondary constructor delegating with scope
grep -rn "scope = AutoCloseableCoroutineScope()" shared/src/commonMain/

# Direct viewModelScope usage (should use injected scope instead)
grep -rn "viewModelScope.launch" shared/src/commonMain/kotlin/com/singularity/todo/feature/*/presentation/viewmodel/

# Side-effects inside combine (the dangerous pattern, see "Side-Effects-in-Combine Fix")
grep -rn "\.value\s*=" shared/src/commonMain/kotlin/com/singularity/todo/feature/*/presentation/viewmodel/ | \
  grep -v " //\|@\|override\|private val _\|MutableStateFlow\|StateFlow\|SharedFlow"
```

---

## Standard Migration — 4 Steps

### Step 1: Identify all constructor parameters

Read the full constructor. Note which are:
- Dependencies (repos, use cases, etc.)
- Runtime values (IDs, modes, etc.)
- The old `scopeOverride: CoroutineScope? = null` or `scope: AutoCloseableCoroutineScope`
- `sharingStarted: () -> SharingStarted` — if present, goes AFTER `scope`

### Step 2: Move `scope` to last param with default, remove `scopeOverride`

**Before:**
```kotlin
class FooViewModel(
    private val deps: FooDeps,
    param1: Type1,
    private val scopeOverride: CoroutineScope? = null,  // OLD
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

If your VM already has `init { addCloseable(scope) }` — keep it. If it has `scope.launch { ... }` in init or methods — change from `viewModelScope.launch` to `scope.launch`.

### Step 4: Verify DI module registration

For VMs without runtime params — switch to **explicit `viewModel { }` lambda**:

```kotlin
// BEFORE (worked with secondary ctor pattern, but creates constructor-resolution ambiguity)
viewModelOf(::FooViewModel)

// AFTER (always explicit lambda — Kotlin compiler validates arg assignment)
viewModel { FooViewModel(deps = get(), param1 = get()) }
```

For VMs with runtime params — same pattern, just include the runtime param:

```kotlin
viewModel { (projectId: ProjectId) ->
    ProjectDetailViewModel(
        projectId = projectId,
        projectRepo = get(),
        // ... etc
    )
}
```

**Why `viewModelOf` doesn't work**: Koin's reflection-based DSL cannot disambiguate a `CoroutineScope` bean from a `() -> SharingStarted` parameter when both are in the ctor. Explicit `viewModel { }` lambda forces Kotlin's compiler to validate parameter assignment.

**Why NOT `factory {}`**: factory creates a new instance per `get()` call — no lifecycle scoping, state lost on navigation, memory leaks. Always `viewModel { }`.

---

## Common pitfalls

### Pitfall 1: Forgetting to remove the broken getter

```kotlin
// WRONG — still has broken getter
private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope  // ← remove this

// CORRECT — no getter, scope is plain val
private val scope: AutoCloseableCoroutineScope  // ← plain val, no getter
```

### Pitfall 2: `viewModelScope` from constructor

`viewModelScope` is only available inside ViewModel methods, not the constructor. Migrating to `scope` (default `AutoCloseableCoroutineScope()`) eliminates this issue.

### Pitfall 3: SharingStarted param position

If your VM has `sharingStarted: () -> SharingStarted = { ... }`, it must go AFTER `scope` (both are defaulted, and `scope` is conceptually more fundamental):

```kotlin
class TasksViewModel(
    private val taskRepo: TaskRepository,
    // ... other deps ...
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
    sharingStarted: () -> SharingStarted = { SharingStarted.WhileSubscribed(5000) },
) : ViewModel()
```

### Pitfall 4: Smart-cast failure on class properties

If your `init` block references a class property in a `when` that narrows a sealed type:

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
private suspend fun initEditMode(mode: ScreenMode.Edit) {
    val id = mode.viewId  // OK
}
```

### Pitfall 5: Tests not updating with named args

After refactoring, tests that constructed the VM positionally may break. Use named args for `scope`:

```kotlin
// WRONG — positional, breaks silently if ctor order changes
TasksViewModel(taskRepo, createTask, updateTask, mutations, projectRepo, clock)

// CORRECT — named, safe across ctor reorder
TasksViewModel(
    taskRepo = taskRepo,
    createTask = createTask,
    // ...
    scope = testScope(this),  // ← named `scope` arg from test
)
```

All 12 VM tests in this project use `scope = testScope(this)` — follow this convention.

---

## Side-Effects-in-Combine Fix (advanced)

The 4-step migration above assumes the VM's state flow is the only coroutine work in play. This assumption **breaks** when the VM consumes flows from a repository that owns its own `CoroutineScope(SupervisorJob() + Dispatchers.Default)`.

### Symptom

After migrating the VM constructor to `scope = this` (or `scope = backgroundScope`) and writing tests that collect `vm.state.value`, tests fail with:

```
kotlinx.coroutines.test.UncompletedCoroutinesError: After waiting for 1m,
there were active child jobs
```

**OR** tests hang indefinitely past their 60s timeout.

### Why

The repository-owned scope runs its `stateIn` collector on `Dispatchers.Default` — a real thread pool outside the test dispatcher's `TestScheduler`. `advanceUntilIdle()` does not advance virtual time on `Default`. The upstream feed never emits within the test → state never updates → `UncompletedCoroutinesError`.

### Fix: scope injection at the repository level

See `singularity-todo-coroutine-scopes` skill for the canonical pattern. The short version:

1. Create `createBackgroundScope()` expect/actual function (returns new `CoroutineScope(SupervisorJob() + Dispatchers.Default)`).
2. Add **mandatory** `scope: CoroutineScope` param to the repository's primary constructor (no default).
3. DI registration: `single { MyRepo(get(), get(), createBackgroundScope()) }`.
4. Update Fake factories to also require `scope: CoroutineScope`.
5. Update all direct constructor call sites (commonTest, previews) to pass `createBackgroundScope()` or a `TestScope`.

---

## Migration Order (for a multi-VM MR)

When migrating many VMs in a single MR:

1. **Use this playbook** for each VM
2. **Use `singularity-todo-test-helpers`** when writing tests
3. **Migration sequence** — group by complexity:
   - Simple VMs first (no scopeOverride, just add `scope` default + `init { addCloseable }`)
   - Standard broken VMs next (replace scopeOverride getter pattern)
   - Side-effects-in-combine VMs last (refactor combine → dedicated collect blocks first)

The previous MR (`fac2e38`, `0413ee7`) migrated 25 VMs across all features in two commits. That ordering works.

---

## See Also

- `singularity-todo-testable-vm` — canonical VM shape + DraftState pattern + test patterns
- `singularity-todo-vm-koin-scoping` — `viewModel {}` vs `viewModelOf` decision tree (now updated to reflect default-param pattern)
- `singularity-todo-test-helpers` — standard test helpers for migrated VMs
- `singularity-todo-vm-intent-pattern` — sealed Intent + onIntent dispatcher
- `singularity-todo-coroutine-scopes` — full pattern for scope placement, anti-pattern, lifecycle
- `docs/decisions/2026-09-17-vm-testability-audit.md` — root cause audit
- `docs/decisions/2026-09-21-tier1-interface-cleanup.md` — Tier 1 cleanup ADR

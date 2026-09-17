---
name: singularity-todo-vm-migration-playbook
description: Step-by-step playbook for migrating an existing ViewModel from the broken nullable scopeOverride pattern to the canonical testable 4-arg constructor + secondary ctor shape. Use when retrofitting an old VM with scopeOverride or when the scope getter is broken.
---

# VM Migration Playbook

This playbook documents how to migrate an existing ViewModel in this project from the **old broken pattern** (`scopeOverride: CoroutineScope? = null` + broken getter) to the **canonical testable pattern** (primary ctor takes `scope: CoroutineScope` explicitly; secondary ctor for Koin defaults it to `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`).

**When to use this playbook:**
- The VM has `private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope` (broken getter pattern)
- The VM has `scopeOverride: CoroutineScope? = null` in its constructor
- You need to make an old VM testable

**Reference:** `singularity-todo-testable-vm` for the target canonical shape and rationale.

---

## Before You Start

### Check what type of VM you have

| Type | Characteristics | Migration complexity |
|---|---|---|
| **A. Simple** | No `scopeOverride` at all, no `viewModelScope` direct usage | Minimal — add `scope` to primary ctor + secondary ctor |
| **B. Standard broken** | Has `scopeOverride: CoroutineScope? = null` + broken getter | Mechanical — 5-step checklist below |
| **C. Side-effects-in-combine** | Has `_latestX.value = x` or draft seeding inside a `combine` lambda | Hard — see "Side-Effects-in-Combine Fix" section |
| **D. Pure read-through** | Uses `combine + stateIn(WhileSubscribed)` with no init, no drafts, no intents | No change needed — this is the narrow legitimate exception |

### Detection commands

```bash
# Find VMs with the broken pattern
grep -rn "scopeOverride: CoroutineScope? = null" shared/src/commonMain/

# Find direct viewModelScope usage (should use injected scope instead)
grep -rn "viewModelScope.launch" shared/src/commonMain/kotlin/com/singularity/todo/feature/*/presentation/viewmodel/

# Find side-effects inside combine (the dangerous pattern)
grep -rn "\.value\s*=" shared/src/commonMain/kotlin/com/singularity/todo/feature/*/presentation/viewmodel/ | \
  grep -v " //\|@\|override\|private val _\|MutableStateFlow\|StateFlow\|SharedFlow"
```

---

## Type B: Standard Broken Pattern — 5-Step Migration

### Step 1: Identify all constructor parameters

Read the full constructor. Count parameters. Note which are:
- Dependencies (repos, use cases, etc.)
- Runtime values (IDs, modes, etc.)
- The old `scopeOverride: CoroutineScope? = null`

Example — `TasksViewModel` constructor:
```kotlin
class TasksViewModel(
    private val deps: TasksDeps,
    private val filter: StateFlow<TaskFilter>,
    private val scopeOverride: CoroutineScope? = null,
    sharingStarted: () -> SharingStarted = { SharingStarted.WhileSubscribed(5000) },
) : ViewModel() {
```

### Step 2: Add `scope: CoroutineScope` to primary constructor, remove `scopeOverride`

**Before:**
```kotlin
class FooViewModel(
    private val deps: FooDeps,
    param1: Type1,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope  // BROKEN
```

**After:**
```kotlin
class FooViewModel(
    private val deps: FooDeps,
    param1: Type1,
    private val scope: CoroutineScope,   // ← now explicit, not nullable
) : ViewModel() {
```

### Step 3: Add secondary constructor for Koin

```kotlin
/** Production constructor — Koin uses this. */
constructor(
    deps: FooDeps,
    param1: Type1,
) : this(
    deps = deps,
    param1 = param1,
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
)
```

**Important:** If the VM also has a `sharingStarted: () -> SharingStarted` parameter (like `TasksViewModel`), keep it in the primary constructor — Koin cannot provide it automatically. The secondary constructor omits it (Koin will use the default `{ SharingStarted.WhileSubscribed(5000) }`):

```kotlin
class TasksViewModel(
    private val deps: TasksDeps,
    private val filter: StateFlow<TaskFilter>,
    private val scope: CoroutineScope,
    sharingStarted: () -> SharingStarted = { SharingStarted.WhileSubscribed(5000) },
) : ViewModel() {

    constructor(
        deps: TasksDeps,
        filter: StateFlow<TaskFilter>,
    ) : this(
        deps = deps,
        filter = filter,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        sharingStarted = { SharingStarted.WhileSubscribed(5000) },
    )
}
```

### Step 4: Update all `scope` usages in the VM body

Replace every `viewModelScope` with `scope`:
```kotlin
// Before
viewModelScope.launch { ... }

// After
scope.launch { ... }
```

### Step 5: Verify DI module registration

Check the VM's DI module. Most VMs use explicit `viewModel { ... }` blocks — **no changes needed** (Koin automatically picks the secondary constructor). Only `viewModelOf(::VM)` requires migration to `viewModel { VM(get(), get(), ...) }`.

**DI files that use `viewModelOf` for VMs (need migration):**
- `AgendaDiModule.kt` — `SavedAgendaListViewModel` uses `viewModelOf(::SavedAgendaListViewModel)` → migrate to `viewModel { SavedAgendaListViewModel(get()) }`

---

## Type A: No Scope At All — Minimal Migration

Some VMs (e.g., `ProjectEditorViewModel`, `NotesListViewModel`) don't have `scopeOverride` but use `viewModelScope` directly. Add `scope` to primary ctor + secondary ctor, then replace `viewModelScope` with `scope`.

```kotlin
// Before
class FooViewModel(private val deps: FooDeps) : ViewModel() {
    init {
        viewModelScope.launch { ... }       // ← viewModelScope directly
    }
}

// After
class FooViewModel(
    private val deps: FooDeps,
    private val scope: CoroutineScope,
) : ViewModel() {

    constructor(deps: FooDeps) : this(
        deps = deps,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    init {
        scope.launch { ... }                 // ← injected scope
    }
}
```

---

## Type C: Side-Effects-in-Combine Fix

This is the dangerous migration — it changes runtime behavior, not just constructor shape.

### What it looks like

```kotlin
// BROKEN — side effects inside combine lambda
val state: StateFlow<TaskDetailUiState> = combine(
    projectFlow, tagsFlow, _draftTitle, _draftDescription
) { project, tags, draftTitle, draftDesc ->
    _latestTask.value = task           // ← SIDE EFFECT inside combine
    if (_draftTitle.value.isEmpty()) { // ← CONDITIONAL SIDE EFFECT
        _draftTitle.value = task.title
    }
    TaskDetailUiState(...)
}.stateIn(scope, SharingStarted.WhileSubscribed(5000), TaskDetailUiState.Loading)
```

### Why it's broken

The `combine` lambda re-runs on **any** upstream emission. The `_draftTitle.value = task.title` line silently overwrites user edits whenever any unrelated flow emits. Reminder ticks, tag changes, checklist updates — all of them can clobber user drafts.

### The fix: extract to dedicated `collect {}` blocks

```kotlin
init {
    // Single source of truth — unconditional passthrough cache
    scope.launch {
        deps.taskRepo.watchTask(taskId).collect { task ->
            _latestTask.value = task
        }
    }
    // Seed drafts ONCE on first non-null emission, never again
    scope.launch {
        deps.taskRepo.watchTask(taskId).filterNotNull().collect { task ->
            if (_draftTitle.value.isEmpty()) {
                _draftTitle.value = task.title
            }
            if (_draftDescription.value.isEmpty()) {
                _draftDescription.value = task.description ?: ""
            }
        }
    }
    // Main UI state — pure derivation, no side effects
    scope.launch {
        combine(flowOf(taskId), _retryVersion) { id, _ -> id }
            .flatMapLatest { id -> watchTaskAndRelated(id) }
            .collect { _state.value = it }
    }
}

private fun watchTaskAndRelated(id: TaskId): Flow<TaskDetailUiState> = combine(
    deps.taskRepo.watchTask(id),
    projectFlow, tagsFlow, checklistFlow, reminderFlow, attachmentsFlow, subtasksFlow
) { task, project, tags, checklist, reminder, attachments, subtasks ->
    TaskDetailUiState(task, project, tags, checklist, reminder, attachments, subtasks)
}
```

### Key properties of the fix

1. **`_latestTask.value = task`** — unconditional passthrough cache. Written in dedicated `collect {}` block, not inside `combine`. This is the narrow allowed exception per `singularity-todo-vm-intent-pattern`: unconditional `onEach { cache.value = it }` is a pure passthrough, not derived state.

2. **Draft seeding** — uses `filterNotNull().collect { ... if (isEmpty()) ... }`. This seeds **once**, on first non-null task. After the first seed, `_draftTitle.value` is non-empty, so `isEmpty()` returns false and no further writes happen.

3. **Main UI state** — pure derivation, zero side effects. `_state.value = ...` in a `collect {}` block is the correct pattern.

### Regression tests to write

```kotlin
@Test
fun `second upstream emission does not clobber user draft`() = runTest {
    val vm = createVm(taskId, this)
    advanceUntilIdle()

    // User edits the draft
    vm.onIntent(TaskDetailIntent.Domain.SetTitle("User's edit"))
    assertEquals("User's edit", vm._draftTitle.value)

    // Simulate second upstream emission (e.g., reminder tick)
    fakeTaskRepo.emitTask(task.copy(title = "Remote update"))
    advanceUntilIdle()

    // Draft must remain unchanged
    assertEquals("User's edit", vm._draftTitle.value)
}
```

---

## viewModelOf vs viewModel {} — Decision Tree

### The problem

`viewModelOf(::MyViewModel)` uses reflection to find a constructor. When the VM has a single primary constructor, Koin uses it directly. When the VM has **multiple constructors** (primary + secondary), Koin picks one based on what it can provide — but it **cannot provide `CoroutineScope`** as a bean, so it fails.

### When to use each

| Pattern | When |
|---|---|
| `viewModelOf(::Vm)` | VM has **exactly one** constructor (no secondary). All parameters are Koin beans (`get()`). |
| `viewModel { Vm(get(), get(), ...) }` | VM has **2+ constructors**. Explicitly call the secondary (production) ctor. |

### Migration checklist for viewModelOf

```kotlin
// BEFORE (broken when VM has 2 constructors)
viewModelOf(::SavedAgendaListViewModel)

// AFTER (explicit production constructor)
viewModel { SavedAgendaListViewModel(get()) }
```

Add a comment explaining why:
```kotlin
// Note: viewModelOf does not work here — Koin cannot provide CoroutineScope as a bean.
// The secondary constructor creates a Main-immediate scope tied to the ViewModel's lifecycle.
viewModel { SavedAgendaListViewModel(get()) }
```

---

## KMP Considerations

### Dispatchers.Main.immediate caveat

`CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` assumes a Main dispatcher is available on the runtime classpath. This is true for our two targets today:
- **Android** — real `Main` looper
- **JVM Desktop** — `kotlinx-coroutines-swing` / Compose for Desktop's UI dispatcher

If you add a **pure JVM CLI or server target** (no UI dispatcher), this will crash at runtime. The fix is to inject the dispatcher as a parameter, but that is a future architectural decision — not in scope for this migration.

### Preview composables — SupervisorJob leak risk

Preview functions that call the VM constructor directly (e.g., `@Preview fun FooPreview() = FooViewModel(deps, param)`) will create a new `SupervisorJob()` each time. In Android Studio preview pane, previews can be recomposed many times → SupervisorJobs accumulate.

**Check for previews before migrating:**
```bash
grep -rn "@Preview" shared/src/androidMain/ | grep "ViewModel("
```

If found, wrap in `remember` or `DisposableEffect` — but for this migration, previews will use the secondary constructor which creates a short-lived scope. Monitor for leak symptoms (memory growth in preview pane). This is a known limitation, not a migration blocker.

---

## Common Pitfalls

### Pitfall 1: Forgetting to remove the broken getter

```kotlin
// WRONG — still has broken getter
private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope  // ← remove this

// CORRECT — no getter, scope is a plain val
private val scope: CoroutineScope  // ← no getter
```

### Pitfall 2: Smart-cast failure on class properties

If your `init` block references a class property in a `when` that narrows a sealed type:

```kotlin
// BROKEN — smart cast fails because mode is a class property
init {
    scope.launch {
        when (mode) {           // mode narrowed here...
            is ScreenMode.Edit -> initEditMode()  // but this calls a function
        }
    }
}
private suspend fun initEditMode() {
    val id = mode.viewId  // ERROR: mode is back to declared type
}

// FIX — pass narrowed local val to function
init {
    scope.launch {
        when (val m = mode) {   // m is a local val, smart-cast survives
            is ScreenMode.Edit -> initEditMode(m)
        }
    }
}
private suspend fun initEditMode(mode: ScreenMode.Edit) {
    val id = mode.viewId  // OK
}
```

### Pitfall 3: Secondary constructor missing `sharingStarted`

If the VM has a `sharingStarted` parameter with a non-trivial default:
```kotlin
// Primary
class FooViewModel(
    ..., 
    scope: CoroutineScope,
    sharingStarted: () -> SharingStarted = { SharingStarted.WhileSubscribed(5000) },
) : ViewModel()

// WRONG secondary — forgot sharingStarted
constructor(...) : this(..., scope = CoroutineScope(...))  // sharingStarted missing!

// CORRECT secondary — include sharingStarted with its default
constructor(...) : this(
    ...,
    scope = CoroutineScope(...),
    sharingStarted = { SharingStarted.WhileSubscribed(5000) },
)
```

### Pitfall 4: Missing import for Dispatchers.Main

After adding `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`, verify the import:
```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainCoroutineDispatcher
```

---

## Migration Order (for this MR)

When migrating all VMs in a single MR:

1. **Migration playbook (this skill)** — used by the agent for every VM
2. **Test helpers** — `singularity-todo-test-helpers` — used when writing smoke tests
3. **Migration sequence:**
   - Commit 2: 8 simple VMs (TasksViewModel, TaskCreateViewModel, ProjectsViewModel, ProjectEditorViewModel, CalendarViewModel, NotesListViewModel, AgendaViewModel, SavedAgendaListViewModel)
   - Commit 3: TaskDetailViewModel (side-effects fix)
   - Commit 4: ProjectDetailViewModel (side-effects fix + 5× viewModelScope→scope)

---

## See Also

- `singularity-todo-testable-vm` — target canonical shape + rationale
- `singularity-todo-test-helpers` — standard test helpers for migrated VMs
- `singularity-todo-vm-intent-pattern` — `_latestTask` cache + the unconditional passthrough exception
- `singularity-todo-koin-di` — `viewModelOf` vs `viewModel {}` DI conventions
- `docs/decisions/2026-09-17-vm-testability-mr1.md` — ADR for this migration

---

## When this playbook is NOT enough

The 5-step mechanical migration above assumes the VM's state flow is the only coroutine work in play. This assumption **breaks** when the VM consumes flows from a repository or wrapper that **owns its own `CoroutineScope(SupervisorJob() + Dispatchers.Default)`**.

### Symptom

After migrating the VM constructor to `scope = this` (or `scope = backgroundScope`) and writing tests that collect `vm.state.value`, tests fail with:

```
kotlinx.coroutines.test.UncompletedCoroutinesError: After waiting for 1m,
there were active child jobs
```

**OR** tests hang indefinitely past their 60s timeout.

### Why

The repository-owned scope runs its `stateIn` collector on `Dispatchers.Default` — a real thread pool outside the test dispatcher's `TestScheduler`. `advanceUntilIdle()` does not advance virtual time on `Default`. The upstream feed never emits within the test → state never updates → `UncompletedCoroutinesError`.

The VM constructor migration **looks correct** but produces no testability improvement because the root cause is upstream.

### Real example (fixed in 2026-09-17)

`CurrentUser`, `ProfileAwareCurrentUser`, `ProfileRepositoryImpl` all hosted `stateIn` on a private `CoroutineScope(Dispatchers.Default)`. After VM constructor migration, tests hung — root cause was not the VM, it was these three classes.

### Fix: scope injection at the repository level

See `singularity-todo-coroutine-scopes` skill for the canonical pattern. The short version:

1. Create `createBackgroundScope()` expect/actual function (returns new `CoroutineScope(SupervisorJob() + Dispatchers.Default)`).
2. Add **mandatory** `scope: CoroutineScope` param to the repository's primary constructor (no default).
3. DI registration: `single { MyRepo(get(), get(), createBackgroundScope()) }`.
4. Update Fake factories to also require `scope: CoroutineScope`.
5. Update all direct constructor call sites (commonTest, previews) to pass `createBackgroundScope()` or a `TestScope`.

### How to detect this anti-pattern before starting

Before applying the 5-step migration, grep:

```bash
grep -rn "CoroutineScope(SupervisorJob\|CoroutineScope(Dispatchers" \
  shared/src/commonMain --include="*.kt"
```

Any hit in a class that hosts `stateIn` is a red flag. Fix the scope ownership **first**, then apply the VM migration playbook.

### When to skip the playbook entirely

If the repository consuming the flow uses a private `CoroutineScope` for `stateIn` AND you can't refactor it (e.g. external library), the playbook cannot deliver testability wins. Don't waste time migrating the VM — fix the upstream first.

## See also (extended)

- `singularity-todo-coroutine-scopes` — full pattern for scope placement, anti-pattern, lifecycle
- `docs/decisions/2026-09-17-vm-testability-audit.md` — root cause audit
- `docs/decisions/2026-09-17-vm-testability-mr1.md` — original ADR (note: this was rolled back; superseded by audit)

---
name: singularity-todo-testable-vm
description: Testable ViewModel pattern for Singularity Todo KMP app. Use when writing a new ViewModel, when a VM has hard-to-test combine/stateIn logic, or when existing VM tests are flaky. Covers the canonical shape (primary ctor with `scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope()` default, no secondary ctor), side-effect extraction, DraftState pattern, and plain MutableStateFlow (no stateIn/combine). Documents the BAN list (stateIn, viewModelScope, runBlocking).
---

# Testable ViewModel Pattern

ViewModels in this project must be **easy to test**. The single biggest source of test pain is reaching for `combine(...).stateIn(scope, WhileSubscribed(...))` **by default**, regardless of what the VM actually does. `stateIn` conflates two unrelated concerns — state derivation and subscription lifecycle — and forces tests to use Turbine and subscription-triggering tricks to observe anything.

This skill documents the **canonical testable pattern** derived from the v3 audit (2026-09-23), including the BAN list enforced by detekt rules and the side-effect extraction technique discovered during the `TaskDetailViewModel` migration.

---

## The Default Rule

> **A stateful VM's `state` is a plain `MutableStateFlow<X>`. No `combine`, no `stateIn`, no `WhileSubscribed`. Initial state is written explicitly in `init` or in dedicated init methods.**

If you need to combine two flows to derive state, **either** combine them once in `init {}` and write to `_state.value = ...`, **or** expose the inputs as separate flows and let the Composable derive state via `combine { ... }.collectAsStateWithLifecycle()`.

For pure read-through VMs (no init, no intents, just `flow.map { … }.stateIn(WhileSubscribed)`), `stateIn` remains appropriate — see the comparison table at the end of the skill.

---

## The Canonical VM Template (2026-09-23)

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class MyViewModel(
    private val deps: MyDeps,
    private val mode: MyScreenMode,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {

    init {
        addCloseable(scope)  // Tier-1 cleanup: scope cancels when VM cleared
    }

    // ─── Plain MutableStateFlow, no stateIn ────────────────────────────────
    private val _state = MutableStateFlow<MyUiState>(MyUiState.Loading)
    val state: StateFlow<MyUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<MyEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    init {
        scope.launch {
            // populate _state via explicit assignments, not inside combine operators
        }
    }
}
```

**Key properties:**

1. **`_state` is plain `MutableStateFlow<X>`** — emits synchronously when `.value` is set. No `stateIn`.
2. **`init` block launches coroutines on the injected `scope`** — sequential, predictable order.
3. **No side effects inside `combine`/`flatMapLatest`** — `_state.value = ...` happens in dedicated functions or collectors, not inside a flow operator.
4. **`scope` is LAST** in the primary ctor — after all non-defaulted deps.
5. **Default param = `AutoCloseableCoroutineScope()`** — no secondary constructor needed.

---

## Why `AutoCloseableCoroutineScope` (not `CoroutineScope`)?

`AutoCloseableCoroutineScope` implements both `CoroutineScope` and `AutoCloseable`. This lets us register cleanup via `addCloseable(scope)` (the ViewModel lifecycle 2.8+ API) instead of overriding `onCleared()`. The default ctor `AutoCloseableCoroutineScope()` uses `createBackgroundScope()` internally (a `CoroutineScope(SupervisorJob() + Dispatchers.Default)` for production, controllable per-test).

See `core/coroutines/AutoCloseableCoroutineScope.kt` for the full rationale.

---

## The Test

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class MyViewModelTest {

    private val fakeRepo = FakeMyRepository()
    private val fakeCurrentUser = FakeProfileAwareCurrentUser()
    private val deps = MyDeps(repo = fakeRepo, currentUser = fakeCurrentUser, clock = Clock)

    private fun createVm(
        mode: MyScreenMode,
        scope: CoroutineScope = this,  // ← pass TestScope receiver directly
    ) = MyViewModel(
        deps = deps,
        mode = mode,
        scope = scope,
    )

    @Test
    fun editModeLoadsAndSeedsDraft() = runTest {
        fakeRepo.upsertSync(makeMyEntity(title = "Test"))

        val vm = createVm(MyScreenMode.Edit(id))
        advanceUntilIdle()

        // Direct read. No Turbine. No expectMostRecentItem. No waitForState.
        val state = vm.state.value
        assertIs<MyUiState.Editing>(state)
        assertEquals("Test", state.draft.name)
    }
}
```

No Turbine, no `expectMostRecentItem`, no `awaitItem`, no `waitForState`.

---

## Critical Pitfall: `backgroundScope` vs `this`

```kotlin
@Test
fun something() = runTest {
    val vm = createVm(mode, this)            // ✅ CORRECT
    val vm = createVm(mode, backgroundScope)  // ❌ WRONG — different cancellation lifecycle
}
```

`backgroundScope` and the TestScope receiver (`this`) share the same underlying `TestDispatcher` and scheduler — `advanceUntilIdle()` does advance coroutines launched on `backgroundScope`. The dispatcher is not the problem.

The real problem is **lifecycle and cancellation order**:

- `backgroundScope` exists for coroutines that are meant to outlive the test body. `runTest` cancels it as its very last step.
- If the VM under test is launched on `backgroundScope`, any assertion you make about ordering relative to the test's own cleanup can behave differently than you expect.

**Rule**: in test methods, pass `this` (the implicit `TestScope` receiver of `runTest`) as the VM's scope. Reserve `backgroundScope` for genuinely long-lived helper coroutines in the test infrastructure (e.g. a fake that polls or emits on a timer) — never for the object under test itself.

---

## Side-Effect Extraction Pattern

When a VM reads from a repository flow **and** mutates local state, do NOT do both inside a single `combine`/`flatMapLatest`. Instead, use a **dedicated collector** that populates the local cache first:

### ❌ WRONG — side effect inside `flatMapLatest`

```kotlin
// TaskDetailViewModel — BROKEN
private val _latestTask = MutableStateFlow<Task?>(null)

init {
    scope.launch {
        taskId.flatMapLatest { id ->
            deps.taskRepo.observe(id)  // upstream
        }.collect { task ->
            _latestTask.value = task   // ← SIDE EFFECT inside flatMapLatest
        }
    }
    // Later: titleEdits.debounce().combine(_latestTask) { ... } ← stale closure risk
}
```

### ✅ CORRECT — dedicated collector for the cache

```kotlin
// TaskDetailViewModel — FIXED
private val _latestTask = MutableStateFlow<Task?>(null)

init {
    // Dedicated collector: populate _latestTask cache BEFORE the state chain
    scope.launch {
        taskId.flatMapLatest { deps.taskRepo.observe(it) }
            .filterNotNull()
            .collect { _latestTask.value = it }
    }
    // State chain reads from cache (not from upstream directly)
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

**Why this matters**: If `_latestTask.value = task` runs inside `flatMapLatest`'s lambda, it executes on every upstream emission, even when the debounce chain hasn't finished. By extracting it to a separate collector that runs first, `_latestTask` always holds the latest value before the debounce logic reads it.

**Same pattern for `ProjectDetailViewModel`**: `_latestProject.value = project` extracted to a separate `projectFlow.collect {}` collector before the `combine` chain that derives the UI state.

---

## `extraBufferCapacity` on `*Event` SharedFlows — canonical reference

**Defaults recap.** `MutableSharedFlow` ships with `replay = 0`, `extraBufferCapacity = 0`,
`onBufferOverflow = BufferOverflow.SUSPEND`.

**What goes wrong with defaults.** Two distinct failure modes:

1. **Race across recomposition / rotation / screen tear-down.** `emit()` is a suspend function: with zero buffer it suspends until a collector is active. If the VM emits at the exact moment the old collector has unsubscribed and the new one hasn't subscribed yet, `emit()` suspends waiting for a collector. If the emitting coroutine is then cancelled (VM's scope tears down) before a collector attaches, **the event is lost with the cancelled coroutine**.

2. **Bursts.** Several events fired in quick succession suspend the emitting coroutine on each other if there's no buffer.

**This project's convention:**
- `extraBufferCapacity = 4` for `*UiEvent` / `*Event` SharedFlows carrying meaningful payloads (errors, dialogs, navigation). 4 matches the project's real burst size.
- `extraBufferCapacity = 1` for payload-less "pulse" signals (`Unit`-typed, e.g. `savedPulse`).

Both use `replay = 0` — old events are not redelivered to new subscribers.

---

## BAN List (enforced by detekt rules)

| Pattern | Rule | Severity | Why |
|---|---|---|---|
| `stateIn(WhileSubscribed(...))` in VMs with init/drafts | Use plain `MutableStateFlow` | Error | Hard to test; keeps upstream active 5s after unsubscribe |
| `viewModelScope.launch` in production | `NoViewModelScopeInProductionRule` | Warning* | Not injectable; not testable |
| `runBlocking { }` in production | `NoRunBlockingRule` | Warning* | Blocks thread; not testable |
| Side effect inside `combine`/`flatMapLatest` | N/A (manual) | Error | TOCTOU race, stale closures |

*Warning mode for now; move to error after baseline via `just detekt-baseline`.

---

## Anti-Patterns to Avoid

### ❌ `combine + stateIn(WhileSubscribed)` used as the default for stateful VMs

```kotlin
// DON'T — hard to test, requires Turbine
val state: StateFlow<X> = combine(flow1, flow2) { a, b -> compute(a, b) }
    .stateIn(scope, SharingStarted.WhileSubscribed(5000), Initial)
```

**Problems:**
- `WhileSubscribed(5000)` delays upstream start by 5s in tests.
- Tests must use Turbine to subscribe (which triggers upstream).
- Any side effect inside `combine` (e.g., `_state.value = seeded`) re-runs on every upstream emission.

**When `stateIn` IS appropriate:** pure read-through VMs where the entire state is derived from a single repo flow (e.g., `AgendaViewModel`). Tests will need Turbine — but document the testability tradeoff.

### ❌ Side effects inside `combine`

```kotlin
// DON'T — `_draft.value = seeded` re-runs on every emission
val state = combine(_draft, repoFlow) { draft, view ->
    if (!draft.initialized) {
        _draft.value = seeded    // ← SIDE EFFECT IN COMBINE
        Editing(...)
    } else Editing(...)
}.stateIn(...)
```

**Fix:** extract `seed()` to a regular method, call it once from `init {}` or use the dedicated collector pattern above.

### ❌ `viewModelScope` from constructor

```kotlin
// DON'T — viewModelScope is only available inside ViewModel methods
class MyViewModel : ViewModel() {
    private val scope: CoroutineScope = viewModelScope  // ERROR: 'this' not initialized
}
```

**Fix:** Use `AutoCloseableCoroutineScope` as a default param in the primary constructor.

### ❌ Stale closures on `_state`

```kotlin
// DON'T — `view` captured from outside `emitEditingState()` is stale
private fun emitEditingState(view: SavedAgendaView? = null) {
    _state.value = Editing(view ?: ..., draft, ...)
}

// FIX — read current state inside the function
private fun emitEditingState() {
    val current = _state.value
    _state.value = Editing(
        view = (current as? SavedAgendaViewState.Editing)?.view,
        draft = draftState.current,
        ...
    )
}
```

### ❌ Smart-cast failure on class properties

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

---

## The DraftState Pattern

When the VM has **editable draft state** (a form, an editor, anything with local dirty tracking), extract it into a separate pure class:

```kotlin
class DraftState(initial: Draft = Draft.empty()) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<Draft> = _state.asStateFlow()
    val current: Draft get() = _state.value

    /** Idempotent seed — only sets if not already initialized. */
    fun seed(draft: Draft) {
        if (_state.value.initialized) return
        _state.value = draft
    }

    fun setName(name: String) { _state.update { it.copy(name = name) } }
}
```

**Why extract DraftState?**
- **Pure unit testing** — `DraftStateTest` exercises seed/setName/reorderSections with NO mocks, NO DI, NO ViewModel.
- **Single source of truth** — no risk of `_draft.value` getting out of sync with what `combine` produces.
- **Easy to mock** — in screen previews, replace the VM with a fake that holds a hand-rolled DraftState.

---

## When to Use Each Pattern

| Pattern | Use when |
|---|---|
| **Plain `MutableStateFlow` + `init { scope.launch { ... } }`** | Default for VMs with initialization + intents (Create/Edit/Delete) |
| **`DraftState` extracted class** | VM has editable form state with `isDirty` tracking |
| **`combine + stateIn(WhileSubscribed)`** | Pure read-through VM (no init, no intents) — e.g., `AgendaViewModel`. Tests will need Turbine. |
| **Dedicated collector for upstream cache** | VM needs to read from repo AND mutate local state (e.g., `TaskDetailViewModel`, `ProjectDetailViewModel`) |

**Decision rule:** if your VM has a `state.value = ...` line anywhere, you don't need `combine`. Just call it from `init {}` or from `onIntent`.

---

## Concrete VMs Following This Pattern

All 25+ VMs in the project follow this canonical shape as of 2026-09-23 (commits `refactor/test-standards-v2`). The reference implementations:

| VM | File | Notes |
|---|---|---|
| `TaskDetailViewModel` | `feature/tasks/presentation/viewmodel/TaskDetail.kt` | Side-effects extracted from `combine`; `_latestTask` cache in dedicated `collect {}` |
| `ProjectDetailViewModel` | `feature/projects/presentation/viewmodel/ProjectDetailViewModel.kt` | Side-effects extracted from `combine`; `projectFlow`, `parentOptionsFlow`, `availableTasksFlow` as separate collectors |
| `SavedAgendaViewModel` | `feature/agenda/presentation/viewmodel/SavedAgendaViewModel.kt` | Primary reference — all patterns |
| `AgendaViewModel` | `feature/agenda/presentation/viewmodel/AgendaViewModel.kt` | Pure read-through → `stateIn(WhileSubscribed)` — legitimate exception |
| `ProjectsViewModel` | `feature/projects/presentation/viewmodel/ProjectsViewModel.kt` | Pure read-through → `stateIn(WhileSubscribed)` — legitimate exception |
| `CalendarViewModel` | `feature/calendar/presentation/viewmodel/CalendarViewModel.kt` | Pure read-through → `stateIn(WhileSubscribed)` — legitimate exception |

---

## See Also

- `singularity-todo-vm-migration-playbook` — migrating old `stateIn` VMs to canonical shape
- `singularity-todo-test-helpers` — `testScope(...)` helper, three test shapes, `awaitState`
- `singularity-todo-koin-dsl` — canonical Koin 4.x DSL: `viewModelOf` vs `factory`, `koinViewModel` vs `koinInject`
- `singularity-todo-feature-scaffold` — canonical 7-file feature template
- `docs/decisions/2026-09-23-test-standards-enforcement.md` — full ADR documenting all findings
- `docs/decisions/2026-09-18-testing-best-practices.md` — testing principles

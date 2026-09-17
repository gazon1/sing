---
name: singularity-todo-testable-vm
description: Testable ViewModel pattern for the Singularity Todo KMP app. Use when writing a new ViewModel, when a VM has hard-to-test combine/stateIn logic, or when existing VM tests are flaky. Covers the DraftState pattern, scope injection via 4-arg constructor, plain MutableStateFlow (no stateIn/combine), and the simple `vm.state.value + advanceUntilIdle()` test pattern.
---

# Testable ViewModel Pattern

ViewModels in this project must be **easy to test**. The single biggest source of test pain is reaching for `combine(...).stateIn(scope, WhileSubscribed(...))` **by default**, regardless of what the VM actually does. `stateIn` conflates two unrelated concerns — state derivation and subscription lifecycle — and forces tests to use Turbine and subscription-triggering tricks to observe anything.

This is the right tool for a narrow case: a VM that is a **pure read-through** over a single upstream flow, with no init-time side effects, no drafts, no intents that mutate local state. For everything else — anything with `init` logic, multi-step setup, or editable draft state — default to a plain `MutableStateFlow` written to explicitly. Pick the pattern based on what the VM's state *is*, not out of habit.

This skill documents the canonical testable pattern, derived from the MR4 refactor of `SavedAgendaViewModel`.

---

## The Default Rule

> **A stateful VM's `state` is a plain `MutableStateFlow<X>`. No `combine`, no `stateIn`, no `WhileSubscribed`. Initial state is written explicitly in `init` or in dedicated init methods.**

If you need to combine two flows to derive state, **either** combine them once in `init {}` and write to `_state.value = ...`, **or** expose the inputs as separate flows and let the Composable derive state via `combine { ... }.collectAsStateWithLifecycle()`.

For pure read-through VMs (no init, no intents, just `flow.map { … }.stateIn(WhileSubscribed)`), `stateIn` remains appropriate — see the comparison table at the end of the skill.

---

## The Testable VM Template

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class SavedAgendaViewModel(
    private val deps: SavedAgendaDeps,
    private val mode: SavedAgendaScreenMode,
    private val seedStore: SavedAgendaSeedStore,
    private val scope: CoroutineScope,                       // ← injected
) : ViewModel() {

    /** Production constructor — Koin uses this. */
    constructor(
        deps: SavedAgendaDeps,
        mode: SavedAgendaScreenMode,
        seedStore: SavedAgendaSeedStore,
    ) : this(
        deps = deps,
        mode = mode,
        seedStore = seedStore,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    // Why Dispatchers.Main.immediate?
    // - This project ships Android (real `Main`) and JVM Desktop (Main is provided
    //   by `kotlinx-coroutines-swing` / Compose for Desktop's UI dispatcher).
    // - `immediate` posts to the current thread if already on Main, avoiding an
    //   unnecessary re-post for fast UI updates (e.g. saving `DraftState.setName`).
    // - It assumes a Main dispatcher is available on the runtime classpath. This
    //   is true for our two targets today. If you add a target without a Main
    //   dispatcher (pure JVM CLI, server, etc.), swap this for a different
    //   dispatcher — DO NOT add a `serviceLoader` fallback here.
    // - Koin's VM factory creates one of these scopes per VM instance. It is
    //   cancelled when the ViewModel is cleared (Koin's `viewModel { }` ties its
    //   lifecycle to the closest `ViewModelStoreOwner`).

    // ─── Plain MutableStateFlow, no stateIn ────────────────────────────────
    private val _state = MutableStateFlow<SavedAgendaViewState>(SavedAgendaViewState.Loading)
    val state: StateFlow<SavedAgendaViewState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SavedAgendaEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    // Why `extraBufferCapacity = 4` and not 1 / UNLIMITED?
    // - 1: `tryEmit` returns false the moment two events are produced back-to-back
    //      faster than the screen collector can drain them. With rapid user input
    //      (e.g. delete-tap-delete-tap) you lose events.
    // - UNLIMITED: an unbounded buffer is a memory leak waiting to happen — a hot
    //      producer with a dead collector will accumulate events forever.
    // - 4: empirically matches the project's real burst size (delete, save, error
    //      notification, one more within the same UI frame). Small enough that a
    //      stale-event pile-up is bounded and visible in a heap dump if it ever
    //      happens; large enough that legitimate bursts don't get dropped.
    // This value is project convention — keep it consistent across VMs.

    val draftState = DraftState()  // ← separate editable state holder

    init {
        scope.launch {
            when (val m = mode) {
                is SavedAgendaScreenMode.Edit   -> initEditMode(m)
                is SavedAgendaScreenMode.Create -> initCreateMode(m)
            }
        }
    }

    private suspend fun initEditMode(mode: SavedAgendaScreenMode.Edit) {
        val userId = deps.currentUser.scopedUserId.first().value
        val view = deps.repo.watchById(mode.viewId, userId).first()
        if (view == null) {
            _state.value = SavedAgendaViewState.NotFound
            return
        }
        val sections = decodeSections(view.sectionsJson)
        val draft = Draft(view.name, sections ?: emptyList(), view.name, sections ?: emptyList(), initialized = true)
        draftState.seed(draft)
        _state.value = SavedAgendaViewState.Editing(view, draftState.current, sections?.size)
    }

    fun onIntent(intent: SavedAgendaIntent) {
        when (intent) {
            is SavedAgendaIntent.NameChanged -> { draftState.setName(intent.name); emitEditingState() }
            is SavedAgendaIntent.SectionsReordered -> { draftState.reorderSections(intent.sections); emitEditingState() }
            is SavedAgendaIntent.Save -> onSave()
            is SavedAgendaIntent.Delete -> onDelete()
        }
    }

    private fun emitEditingState() {
        _state.value = SavedAgendaViewState.Editing(
            view = (_state.value as? SavedAgendaViewState.Editing)?.view,
            draft = draftState.current,
            sectionCount = draftState.current.sections.size,
        )
    }
    // ...
}
```

**Key properties:**

1. **`_state` is plain `MutableStateFlow<X>`** — emits synchronously when `.value` is set. No `stateIn`.
2. **`init` block launches one coroutine on the injected `scope`** — sequential, predictable order.
3. **No side effects inside `combine`** — `_state.value = ...` happens in dedicated functions, not inside a flow operator.
4. **4-arg primary constructor takes `CoroutineScope`** — production uses a Koin-friendly scope, tests pass `this` (the test's scope).

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
    fun reorderSections(sections: List<Section>) { _state.update { it.copy(sections = sections) } }
}

data class Draft(
    val name: String,
    val sections: List<Section>,
    val originalName: String,
    val originalSections: List<Section>,
    val initialized: Boolean = false,
) {
    /** Derived — no separate flag needed. */
    val isDirty: Boolean get() = name != originalName || sections != originalSections
}
```

**Why extract DraftState?**

- **Pure unit testing** — `DraftStateTest` exercises seed/setName/reorderSections with NO mocks, NO DI, NO ViewModel.
- **Single source of truth** — no risk of `_draft.value` getting out of sync with what `combine` produces.
- **Easy to mock** — in screen previews, replace the VM with a fake that holds a hand-rolled DraftState.

---

## The Test

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class SavedAgendaViewModelTest {

    private val fakeRepo = FakeSavedAgendaViewsRepository()
    private val fakeCurrentUser = FakeProfileAwareCurrentUser()
    private val seedStore = SavedAgendaSeedStore()

    private fun createVm(mode: SavedAgendaScreenMode, scope: CoroutineScope) = SavedAgendaViewModel(
        deps = SavedAgendaDeps(repo = fakeRepo, currentUser = fakeCurrentUser, clock = Clock),
        mode = mode,
        seedStore = seedStore,
        scope = scope,                    // ← pass test scope directly
    )

    @Test
    fun editModeLoadsViewAndSeedsDraft() = runTest {
        val viewId = SavedAgendaViewId.generate()
        fakeRepo.upsertSync(makeView(id = viewId, name = "My View"))

        // THIS — pass the test scope. NOT backgroundScope.
        val vm = createVm(SavedAgendaScreenMode.Edit(viewId), this)
        advanceUntilIdle()

        // Direct read. No Turbine. No expectMostRecentItem. No waitForState.
        val state = vm.state.value
        assertIs<SavedAgendaViewState.Editing>(state)
        assertEquals("My View", state.draft.name)
        assertFalse(state.draft.isDirty)
    }

    @Test
    fun nameChangedSetsIsDirty() = runTest {
        // ... seed view, createVm
        vm.onIntent(SavedAgendaIntent.NameChanged("Modified"))

        val state = vm.state.value                       // ← just read .value
        assertEquals("Modified", state.draft.name)
        assertTrue(state.draft.isDirty)
    }
}
```

**The whole test pattern is 3 lines per case:**
1. `runTest { ... }`
2. `vm.state.value` (or `advanceUntilIdle()` first if you want to flush init)
3. Standard assertions

No Turbine, no `expectMostRecentItem`, no `awaitItem`, no `waitForState`.

---

## Critical Pitfall: `backgroundScope` vs `this`

```kotlin
@Test
fun something() = runTest {
    val vm = createVm(mode, this)            // ✅ CORRECT
    val vm = createVm(mode, backgroundScope)  // ❌ WRONG — see below
}
```

`backgroundScope` and the TestScope receiver (`this`) share the same underlying `TestDispatcher` and scheduler — `advanceUntilIdle()` does advance coroutines launched on `backgroundScope`. The dispatcher is not the problem.

The real problem is **lifecycle and cancellation order**:

- `backgroundScope` exists for coroutines that are meant to outlive the test body — long-running work that should be automatically cancelled after the test completes (e.g. a `while(true)` polling loop you never explicitly stop). `runTest` cancels it as its very last step.
- If the VM under test is launched on `backgroundScope`, any assertion you make about ordering relative to the test's own cleanup, or any second `advanceUntilIdle()` / `runCurrent()` call issued after the main test body considers itself "done", can behave differently than you expect — because you're now reasoning about two independently-cancelled coroutine hierarchies instead of one.
- More practically: mixing scopes like this makes failures non-obvious. A test can pass or hang depending on unrelated scheduling details, rather than deterministically reflecting what the VM does. `this` keeps everything in a single, straightforward hierarchy that `runTest` fully controls and reports on.

**Rule**: in test methods, pass `this` (the implicit `TestScope` receiver of `runTest`) as the VM's scope. Reserve `backgroundScope` for genuinely long-lived helper coroutines in the test infrastructure (e.g. a fake that polls or emits on a timer) that should be auto-cancelled at teardown — never for the object under test itself.

---

## Anti-Patterns to Avoid

### ❌ `combine + stateIn(WhileSubscribed)` used as the default for stateful VMs

(See "When to Use Each Pattern" below — this anti-pattern applies when the VM has init logic or draft state, not to pure read-through VMs like `AgendaViewModel`.)

```kotlin
// DON'T — hard to test, requires Turbine
val state: StateFlow<X> = combine(flow1, flow2) { a, b -> compute(a, b) }
    .stateIn(scope, SharingStarted.WhileSubscribed(5000), Initial)
```

**Problems:**
- `WhileSubscribed(5000)` delays upstream start by 5s in tests.
- Tests must use Turbine to subscribe (which triggers upstream).
- `combine` runs on the wrong dispatcher if scope is wrong.
- Any side effect inside `combine` (e.g., `_state.value = seeded`) re-runs on every upstream emission.

**When `stateIn` IS appropriate:** pure read-through VMs where the entire state is derived from a single repo flow (e.g., `AgendaViewModel`). In those cases, use `SharingStarted.WhileSubscribed()` and accept that tests need Turbine — but document the testability tradeoff.

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

**Fix:** extract `seed()` to a regular method, call it once from `init {}` or `initEditMode()`.

### ❌ Relying on smart-cast of a class property across function calls

```kotlin
// DOESN'T COMPILE:
// "Smart cast to 'SavedAgendaScreenMode.Edit' is impossible, because 'mode' is a property
// that has an open or custom getter"
init {
    scope.launch {
        when (mode) {
            is SavedAgendaScreenMode.Edit   -> initEditMode()   // mode narrowed to Edit here...
            is SavedAgendaScreenMode.Create -> initCreateMode() // ...but narrowing doesn't carry in
        }
    }
}

private suspend fun initEditMode() {
    val view = deps.repo.watchById(mode.viewId, ...).first()  // ERROR: mode is back to the
    // declared type SavedAgendaScreenMode here — the compiler can't prove it's still Edit,
    // because `mode` is a class property, not a local val, and it could in principle change
    // (or be overridden by a subclass) between the `when` check and this access.
}
```

This isn't about crossing a function boundary per se — a smart-cast on a local `val` survives calls to other functions just fine, as long as the compiler can prove nothing reassigns it in between. The issue is specifically that `mode` is a class property: Kotlin only smart-casts properties when it can prove, at compile time, that no code path (including from another thread, or an overriding getter) could change the value between the check and the use — and it can't prove that across a function call.

**Fix:** capture the narrowed value in a local `val` (via `when (val m = mode)`) and pass *that* local — not the property — into the function. A local `val` smart-casts reliably because the compiler can track that no reassignment happens before it's used:

```kotlin
init {
    scope.launch {
        when (val m = mode) {                          // m is a local val, narrowed
            is SavedAgendaScreenMode.Edit   -> initEditMode(m)
            is SavedAgendaScreenMode.Create -> initCreateMode(m)
        }
    }
}

private suspend fun initEditMode(mode: SavedAgendaScreenMode.Edit) { ... }
private fun initCreateMode(mode: SavedAgendaScreenMode.Create) { ... }
```

### ❌ `viewModelScope` from constructor

```kotlin
// DON'T — viewModelScope is only available inside ViewModel methods
class MyViewModel : ViewModel() {
    private val scope: CoroutineScope = viewModelScope  // ERROR: 'this' not initialized
}
```

**Fix:** use `viewModelScope` lazily via a getter:
```kotlin
class MyViewModel(scopeOverride: CoroutineScope? = null) : ViewModel() {
    private val scope: CoroutineScope = scopeOverride ?: viewModelScope
}
```

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

---

## When to Use Each Pattern

| Pattern | Use when |
|---|---|
| **Plain `MutableStateFlow` + `init { scope.launch { ... } }`** | Default for VMs with initialization + intents (Create/Edit/Delete) |
| **`DraftState` extracted class** | VM has editable form state with `isDirty` tracking |
| **`combine + stateIn(WhileSubscribed)`** | Pure read-through VM (no init, no intents) — e.g., `AgendaViewModel`. Tests will need Turbine. |
| **Reducer in pure function** | VM has purely local UI state (e.g., `TaskEditorUiState.reduce()`) with NO repository side effects |

**Decision rule:** if your VM has a `state.value = ...` line anywhere, you don't need `combine`. Just call it from `init {}` or from `onIntent`.

---

## Reference Implementation

- `SavedAgendaViewModel` — canonical example of all the above (in `feature/agenda/presentation/viewmodel/`).
- `SavedAgendaViewModelTest` — 14 tests, all using `runTest` + `advanceUntilIdle()` + direct `.state.value`.
- `AgendaViewModel` — exception case, uses `combine + stateIn(WhileSubscribed)` because it's pure read-through (no draft, no intents).

## See Also

- `singularity-todo-vm-intent-pattern` — sealed Intent + onIntent dispatcher
- `singularity-todo-feature-scaffold` — canonical 7-file feature template
- `docs/decisions/2026-09-16-agenda-mr4-saved-views-create-reorder.md` — original ADR for this pattern

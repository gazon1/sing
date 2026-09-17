---
name: singularity-todo-testable-vm
description: Testable ViewModel pattern for the Singularity Todo KMP app. Use when writing a new ViewModel, when a VM has hard-to-test combine/stateIn logic, or when existing VM tests are flaky. Covers the DraftState pattern, scope injection via 4-arg constructor, plain MutableStateFlow (no stateIn/combine), and the simple `vm.state.value + advanceUntilIdle()` test pattern.
---

# Testable ViewModel Pattern

ViewModels in this project must be **easy to test**. The single biggest source of test pain is `combine(...).stateIn(scope, WhileSubscribed(...))` — it conflates two unrelated concerns (state derivation and lifecycle management) and forces tests to use Turbine and orchestration tricks. **Don't do it.**

This skill documents the canonical testable pattern, derived from the MR4 refactor of `SavedAgendaViewModel`.

---

## The Rule

> **A VM's `state` is a plain `MutableStateFlow<X>`. No `combine`, no `stateIn`, no `WhileSubscribed`. Initial state is written explicitly in `init` or in dedicated init methods.**

If you need to combine two flows to derive state, **either** combine them once in `init {}` and write to `_state.value = ...`, **or** expose the inputs as separate flows and let the Composable derive state via `combine { ... }.collectAsStateWithLifecycle()`.

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

    // ─── Plain MutableStateFlow, no stateIn ────────────────────────────────
    private val _state = MutableStateFlow<SavedAgendaViewState>(SavedAgendaViewState.Loading)
    val state: StateFlow<SavedAgendaViewState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SavedAgendaEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

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
    val vm = createVm(mode, backgroundScope)  // ❌ WRONG — coroutines don't advance!
}
```

`backgroundScope` is a child scope of the test's `TestScope`, but it uses its own dispatcher (`StandardTestDispatcher` with a child Job). When you call `advanceUntilIdle()`, the test scheduler advances only coroutines scheduled on the test's main scope — `backgroundScope` coroutines wait forever.

**Rule**: in test methods, pass `this` (the implicit `TestScope` receiver of `runTest`), not `backgroundScope`.

`backgroundScope` is appropriate only for long-lived coroutines that should outlive the test (e.g., real network polling).

---

## Anti-Patterns to Avoid

### ❌ `combine + stateIn(WhileSubscribed)`

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

### ❌ Smart-cast inside private method

```kotlin
// DOESN'T COMPILE — smart cast doesn't survive function boundary
private suspend fun initEditMode() {
    val view = deps.repo.watchById(mode.viewId, ...).first()  // 'mode' is Edit
    // mode is the property; smart cast lost when crossing function
}

private fun initCreateMode() {
    val seed = seedStore.consumeSeed() ?: mode.seed  // 'mode' is Create
    // also fails
}
```

**Fix:** pass the mode as a parameter:
```kotlin
init {
    scope.launch {
        when (val m = mode) {
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

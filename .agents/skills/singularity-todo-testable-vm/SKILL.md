---
name: singularity-todo-testable-vm
description: Testable ViewModel pattern for the Singularity Todo KMP app. Use when writing a new ViewModel, when a VM has hard-to-test combine/stateIn logic, or when existing VM tests are flaky. Covers the current canonical shape (primary ctor with `scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope()` default, no secondary ctor), the DraftState pattern, plain MutableStateFlow (no stateIn/combine), and the simple `vm.state.value + advanceUntilIdle()` test pattern.
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

## The Testable VM Template (current canonical — 2026-09-21)

The canonical shape uses `AutoCloseableCoroutineScope` as a **default param in the primary constructor** — NO secondary constructor, NO `scopeOverride`, NO `viewModelScope` direct usage. Tests pass `scope` explicitly; production uses the default.

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class SavedAgendaViewModel(
    private val deps: SavedAgendaDeps,
    private val mode: SavedAgendaScreenMode,
    private val seedStore: SavedAgendaSeedStore,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {

    init {
        addCloseable(scope)  // Tier-1 cleanup: scope cancels when VM cleared
    }

    // ─── Plain MutableStateFlow, no stateIn ────────────────────────────────
    private val _state = MutableStateFlow<SavedAgendaViewState>(SavedAgendaViewState.Loading)
    val state: StateFlow<SavedAgendaViewState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SavedAgendaEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    // Why `extraBufferCapacity = 4`? See "extraBufferCapacity on *Event SharedFlows"
    // below for the full canonical explanation. TL;DR: MutableSharedFlow defaults to
    // replay=0, extraBufferCapacity=0, BufferOverflow.SUSPEND. emit() suspends waiting
    // for a collector; if the emitting coroutine is cancelled first (rotation,
    // navigation tear-down), the event is lost. 4 empirically matches burst size;
    // 1 fails on 2-in-a-row; UNLIMITED is a memory leak. This value is project
    // convention — keep it consistent across VMs.

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

**Why `AutoCloseableCoroutineScope` (not `CoroutineScope`)?**

`AutoCloseableCoroutineScope` implements both `CoroutineScope` and `AutoCloseable`. This lets us register cleanup via `addCloseable(scope)` (the ViewModel lifecycle 2.8+ API) instead of overriding `onCleared()`. The default ctor `AutoCloseableCoroutineScope()` uses `createBackgroundScope()` internally (a `CoroutineScope(SupervisorJob() + Dispatchers.Default)` for production, controllable per-test).

See `core/coroutines/AutoCloseableCoroutineScope.kt` for the full rationale.

---

## `extraBufferCapacity` on `*Event` SharedFlows — canonical reference

This section is the project-wide source of truth for why VMs use
`MutableSharedFlow<*Event>(extraBufferCapacity = N)` and how to pick `N`. Other skills
(`ui-event-vs-state`, `vm-intent-pattern`, `shared-ui-components`) point here.

**Defaults recap.** `MutableSharedFlow` ships with `replay = 0`, `extraBufferCapacity = 0`,
`onBufferOverflow = BufferOverflow.SUSPEND`.

**What goes wrong with defaults.** Two distinct failure modes:

1. **Race across recomposition / rotation / screen tear-down.** `emit()` is a suspend
   function: with zero buffer it suspends until either a subscriber is actively
   collecting or a buffer slot is free. If the VM emits at the exact moment the old
   collector has unsubscribed and the new one hasn't subscribed yet (e.g. mid-rotation,
   mid-navigation), `emit()` suspends waiting for a collector. If the emitting
   coroutine is then cancelled (e.g. the VM's scope tears down) before a collector
   attaches, **the event is lost with the cancelled coroutine** — not delivered, not
   queued. (`tryEmit()` is a different, non-suspending call that can return `false`
   and silently do nothing on overflow — don't conflate the two when explaining this.)

2. **Bursts.** Several events fired in quick succession (rapid user taps, cascading
   error notifications) suspend the emitting coroutine on each other if there's no
   buffer, serialising emission to collector speed.

**Why a small bound, not UNLIMITED.** An unbounded buffer on an event channel can mask
a bug where events are produced faster than the UI ever consumes them — a hot producer
with a dead collector will accumulate events forever. A small bound surfaces that as
dropped events (via `BufferOverflow.SUSPEND` backpressure) rather than silent unbounded
memory growth, and 4 stale events is visible in a heap dump, unlike an unbounded queue.

**This project's convention:**
- `extraBufferCapacity = 4` for `*UiEvent` / `*Event` SharedFlows carrying meaningful
  payloads (errors, dialogs, navigation). 4 matches the project's real burst size
  (delete, save, error notification, one more within the same UI frame).
- `extraBufferCapacity = 1` for payload-less "pulse" signals (`Unit`-typed, e.g.
  `savedPulse`) where only "did this fire since I last checked" matters and coalescing
  extra emissions is harmless. With `Animatable`-based dedup on the consumer side,
  losing intermediate pulses is fine — only the most recent one matters.

Both are deliberately small bounded numbers. Use `replay = 0` for events — we don't
want to redeliver old events to *new* subscribers; that's a state concern, not an event
concern. If you need replay, use a `StateFlow` instead.

---

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
        scope = testScope(scope),           // ← wrap TestScope in AutoCloseableCoroutineScope
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

The `testScope(scope)` helper from `singularity-todo-test-helpers` wraps `TestScope` in `AutoCloseableCoroutineScope` so it matches the production ctor signature. The whole test pattern is 3 lines per case:
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

**Fix (current canonical):** Use `AutoCloseableCoroutineScope` as a default param in the primary constructor:

```kotlin
class MyViewModel(
    private val deps: MyDeps,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {
    init {
        addCloseable(scope)  // Tier-1 cleanup: scope cancels when VM cleared
    }
}
```

For migration from old `scopeOverride` / secondary ctor patterns, see `singularity-todo-vm-migration-playbook`.

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

## Concrete VMs Following This Pattern

The following VMs have been migrated to the canonical testable shape (primary ctor with `scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope()` default + `init { addCloseable(scope) }` — **no secondary ctor**). Use these as reference when writing new VMs or migrating old ones.

### Canonical (fully testable) — all 25+ VMs in the project

| VM | File | Notes |
|---|---|---|
| `SavedAgendaViewModel` | `feature/agenda/presentation/viewmodel/` | Primary reference — all patterns |
| `TasksViewModel` | `feature/tasks/presentation/viewmodel/TaskList.kt` | `scope` default + `stateIn` replaced with plain `MutableStateFlow` |
| `TaskCreateViewModel` | `feature/tasks/presentation/viewmodel/TaskCreateViewModel.kt` | Plain `MutableStateFlow`; `DraftState` candidate |
| `TaskDetailViewModel` | `feature/tasks/presentation/viewmodel/TaskDetail.kt` | Side-effects extracted from `combine`; `_latestTask` cache in dedicated `collect {}` |
| `ProjectsViewModel` | `feature/projects/presentation/viewmodel/ProjectsViewModel.kt` | Pure read-through → `stateIn` still OK |
| `ProjectEditorViewModel` | `feature/projects/presentation/viewmodel/ProjectEditorViewModel.kt` | `scope` default; was migrated from old `viewModelScope` direct usage |
| `ProjectDetailViewModel` | `feature/projects/presentation/viewmodel/ProjectDetailViewModel.kt` | Side-effects extracted from `combine`; `log: Logger` injected via DI |
| `CalendarViewModel` | `feature/calendar/presentation/viewmodel/CalendarViewModel.kt` | Pure read-through → `stateIn` still OK |
| `NotesListViewModel` | `feature/notes/presentation/viewmodel/NotesListViewModel.kt` | `scope` default; `viewModel { }` lambda |
| `NoteEditor` | `feature/notes/presentation/viewmodel/NoteEditor.kt` | `scope` default + nullable `logger: Logger?` + nullable `improveNote` |
| `NotePreview` | `feature/notes/presentation/viewmodel/NotePreview.kt` | `scope` default |
| `AgendaViewModel` | `feature/agenda/presentation/viewmodel/AgendaViewModel.kt` | Pure read-through → `stateIn(WhileSubscribed)` — legitimate exception |
| `SavedAgendaListViewModel` | `feature/agenda/presentation/viewmodel/SavedAgendaListViewModel.kt` | `viewModel { }` not `viewModelOf` (Koin cannot provide `CoroutineScope`) |
| `SavedAgendaViewModel` | `feature/agenda/presentation/viewmodel/SavedAgendaViewModel.kt` | Runtime param + `scope` default |
| `ChatViewModel` | `feature/ai/chat/ChatViewModel.kt` | `scope` default + `log: Logger` injected via `Logger.withTag(...)` in DI |
| `AiUsageViewModel` | `feature/ai/usage/AiUsageViewModel.kt` | `scope` default |
| `TagsViewModel`, `SearchViewModel`, `SettingsViewModel`, `ArchiveViewModel`, `ProfileSwitcherViewModel`, `AuthViewModel`, `AttachmentsViewModel`, `BackupViewModel`, `ChecklistEditorViewModel`, `StatisticsViewModel` | various | All migrated in `fac2e38` + `0413ee7` MRs |

### Migration status

All 25+ VMs in the project follow this canonical shape as of 2026-09-21 (commits `fac2e38` and `0413ee7`). The canonical shape is **enforced** for all new VMs via this skill and `singularity-todo-vm-migration-playbook`.

### Key deviations from canonical

| VM | Deviation | Rationale |
|---|---|---|
| `AgendaViewModel` | `combine + stateIn(WhileSubscribed)` | Pure read-through — no init, no drafts, no intents. `stateIn` is the correct tool here. Tests use Turbine. |
| `ProjectsViewModel` | `combine + stateIn(WhileSubscribed)` | Same as AgendaViewModel — pure read-through of `watchProjectsWithCounts()`. |
| `CalendarViewModel` | `combine + stateIn(WhileSubscribed)` | Same — pure read-through with `flatMapLatest`. |

These are the **narrow legitimate exceptions** documented in "When to Use Each Pattern" above. They are not anti-patterns — they are the correct tool for their specific case.

## See Also

- `singularity-todo-vm-intent-pattern` — sealed Intent + onIntent dispatcher
- `singularity-todo-vm-koin-scoping` — how to register a VM in Koin (always explicit `viewModel { }`, never `viewModelOf`)
- `singularity-todo-vm-migration-playbook` — migrating old `scopeOverride` / secondary ctor VMs to canonical shape
- `singularity-todo-test-helpers` — `testScope(...)` helper for VM tests
- `singularity-todo-feature-scaffold` — canonical 7-file feature template
- `docs/decisions/2026-09-16-agenda-mr4-saved-views-create-reorder.md` — original ADR for this pattern
- `docs/decisions/2026-09-21-tier1-interface-cleanup.md` — recent MR with the canonical default-param VM migration (25 VMs across all features)

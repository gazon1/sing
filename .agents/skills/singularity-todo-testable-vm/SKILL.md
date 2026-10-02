---
name: singularity-todo-testable-vm
description: Testable ViewModel pattern for Singularity Todo KMP app. Use when writing a new ViewModel, when a VM has hard-to-test combine/stateIn logic, when existing VM tests are flaky, or when a VM screen is stuck on Loading (init-order pitfall). Covers the MviViewModel base class (preferred), IntentActions inline wrapper, DraftState pattern, and the canonical scope-as-default-param pattern. Documents the BAN list (stateIn, viewModelScope, runBlocking).
---

# Testable ViewModel Pattern

ViewModels in this project extend `MviViewModel<S, I, E>` — a local framework base class that provides typed `state`, `events`, `emit()`, `updateState()`, and the `onIntent()` dispatcher out of the box. This eliminates the boilerplate of hand-rolled MVI (separate `_state`, `_events`, `addCloseable`, etc.) and makes all VMs testable the same way.

This skill documents the **canonical testable pattern** including the MviViewModel base, `IntentActions<I>` screen-layer wrapper, the `DraftState` helper, and the scope injection contract.

---

## The Default Rule

> **All new ViewModels should extend `MviViewModel<S, I, E>`. Use the hand-rolled pattern only when you cannot use MviViewModel (e.g., exotic lifecycle requirements).**

`MviViewModel` provides:
- `state: StateFlow<S>` — plain `MutableStateFlow` under the hood
- `events: Flow<E>` — one-shot event channel
- `emit(event: E)` — suspend emit a one-shot event
- `updateState(transform: (S) → S)` — suspend state update
- `onIntent(intent: I)` — abstract dispatcher

---

## The Canonical VM Template (MviViewModel)

```kotlin
class TagsViewModel(
    private val tagRepo: TagsRepository,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : MviViewModel<TagsUiState, TagsIntent, TagsUiEvent>(
    initialState = TagsUiState.Loading,
    scope = scope,
) {

    init { addCloseable(scope) }

    init {
        scope.launch {
            tagRepo.observeAll()
                .catch { updateState { TagsUiState.Error(it.message ?: "Error") } }
                .collect { tags ->
                    updateState {
                        if (tags.isEmpty()) TagsUiState.Empty("") else TagsUiState.Content(tags)
                    }
                }
        }
    }

    override fun onIntent(intent: TagsIntent) {
        when (intent) {
            is TagsIntent.Delete -> scope.launch { delete(intent.id) }
        }
    }

    private suspend fun delete(id: TagId) {
        tagRepo.delete(id).onFailure { emit(TagsUiEvent.ShowError(it.message ?: "Error")) }
    }
}
```

**Key properties:**

1. **`scope` is `private val` and LAST** — MviViewModel's init block calls `addCloseable(scope)`, so `scope` must be stored as a property
2. **Two `init` blocks**: first calls `addCloseable(scope)`, second launches collectors
3. **No manual `_state` or `_events`** — MviViewModel provides them
4. **`emit()` is protected suspend fun** — wrap in `scope.launch {}` when calling from non-suspend context
5. **`updateState(transform: (S) → S)`** is suspend — always called from within a coroutine

---

## Intent/Event Sealed Interfaces

Every MviViewModel VM requires typed Intent and Event hierarchies:

```kotlin
sealed interface TagsIntent : MviIntent {
    data class Delete(val id: TagId) : TagsIntent
}

sealed interface TagsUiEvent : MviEvent {
    data class ShowError(val message: String) : TagsUiEvent
}
```

**`MviIntent` and `MviEvent` are marker interfaces** — they carry no behaviour, only mark the type for `MviViewModel`'s type parameters.

---

## Why `AutoCloseableCoroutineScope` (not `CoroutineScope`)?

`AutoCloseableCoroutineScope` implements both `CoroutineScope` and `AutoCloseable`. This lets us register cleanup via `addCloseable(scope)` (the ViewModel lifecycle 2.8+ API) instead of overriding `onCleared()`. The default ctor `AutoCloseableCoroutineScope()` uses `createBackgroundScope()` internally (a `CoroutineScope(SupervisorJob() + Dispatchers.Default)` for production, controllable per-test).

See `core/coroutines/AutoCloseableCoroutineScope.kt` for the full rationale.

---

## `emit()` from a Non-Suspend Context

`emit()` is `protected suspend fun`, so it cannot be called from a plain `(T) -> Unit`
lambda. The base class solves this: use `emitError` (failure becomes a one-shot event)
or `catchTo` (failure goes somewhere else) — both take the routing lambda as
`suspend`, so `emit` is called directly.

```kotlin
// ✅ Failure becomes a one-shot event — no nested launch
is ProjectDetailIntent.Domain.ToggleTaskPin ->
    emitError("Pin failed", ProjectDetailUiEvent::ShowError) {
        taskRepo.togglePinned(intent.taskId)
    }

// ✅ Failure goes to a state field
catchTo("Delete failed", { msg -> updateState { it.copy(errorMessage = msg) } }) {
    repo.delete(id)
}
```

`catchTo`'s `onError` is `suspend` **on purpose**. It exists so this call site stays a
single expression; the previous `CoroutineScope.fireAndForget` helper took a non-suspend
`onError`, which forced every call site to wrap `emit` in `scope.launch { }`.

For failures that need a success branch as well, keep a plain `vmScope.launch { … }`
with `fold(onSuccess = …, onFailure = …)` — `emitError` only routes the failure.

---

## The Test

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class TagsViewModelTest {

    private val fakeRepo = FakeTagsRepository()
    private fun createVm(scope: CoroutineScope = this) =
        TagsViewModel(tagRepo = fakeRepo, scope = scope)

    @Test
    fun `loads content`() = runTest {
        fakeRepo.upsertSync(makeTag(name = "Work"))

        val vm = createVm(this)
        advanceUntilIdle()

        val state = vm.state.value
        assertIs<TagsUiState.Content>(state)
        assertEquals(1, state.tags.size)
    }
}
```

No Turbine, no `expectMostRecentItem`, no `awaitItem`, no `waitForState`.

---

## IntentActions<I> — Screen-Layer Dispatch Wrapper

Instead of per-feature `@JvmInline value class XxxActions`, use the generic `IntentActions<I>`:

```kotlin
// In Screen
val actions = IntentActions<TagsIntent>(viewModel::onIntent)

// Dispatch an intent
actions(TagsIntent.Delete(tagId))
```

This replaces the old pattern:
```kotlin
// OLD — per-feature Actions
@JvmInline value class TagsActions(private val dispatch: (TagsIntent) -> Unit) {
    operator fun invoke(intent: TagsIntent) = dispatch(intent)
}
val actions = TagsActions(viewModel::onIntent)
```

---

## Critical Pitfall: a property read from `init` must be declared BEFORE it

Kotlin initialises properties in declaration order, and an `init` block sees a
property declared AFTER it as `null` — no compile error, no intrinsic check inside
the same class, because the JVM field is simply not assigned yet. If that `init`
launches a coroutine reading the property, the coroutine dies before its first
emission: **no error state, no event, just a screen on its loading shell forever**.
The concrete instance: `TaskDetailCoordinator` declared `extrasState` after the
`init` that combines over it — the combine died with
`NullPointerException: parameter f8 is null` on a background dispatcher.

```kotlin
class MyViewModel(...) : MviViewModel<...>(...) {
    private val extras = buildExtras()   // ✅ BEFORE init — init reads it

    init { scope.launch { combine(extras, ...).collect { setState(it) } } }
    // private val extras = buildExtras()  // ❌ AFTER init — read as null there
}
```

`MviViewModel`-level tests never catch this (the property is only read from the
init-launched combine); a graph-resolved construction does. The guard is
`TaskDetailCoordinatorGraphTest` — build the VM from the real DI graph and assert
the state leaves `Loading` in real time. ADR: `2026-10-03-merge-regression-fixes.md`.

---

## Critical Pitfall: `backgroundScope` vs `this`

```kotlin
@Test
fun something() = runTest {
    val vm = createVm(this)              // ✅ CORRECT
    val vm = createVm(backgroundScope)  // ❌ WRONG — different cancellation lifecycle
}
```

`backgroundScope` and the TestScope receiver (`this`) share the same underlying `TestDispatcher` — `advanceUntilIdle()` does advance coroutines launched on `backgroundScope`. The problem is **lifecycle and cancellation order**: `backgroundScope` is cancelled as the very last step of `runTest`. For the VM under test, always pass `this` (the implicit `TestScope` receiver).

---

## Side-Effect Extraction Pattern

When a VM reads from a repository flow **and** mutates local state, do NOT do both inside a single `combine`/`flatMapLatest`. Instead, use a **dedicated collector** that populates the local cache first:

### ❌ WRONG — side effect inside `flatMapLatest`

```kotlin
private val _latestTask = MutableStateFlow<Task?>(null)

init {
    scope.launch {
        taskId.flatMapLatest { deps.taskRepo.observe(it) }.collect { task ->
            _latestTask.value = task   // ← SIDE EFFECT inside flatMapLatest
        }
    }
}
```

### ✅ CORRECT — dedicated collector for the cache

```kotlin
private val _latestTask = MutableStateFlow<Task?>(null)

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

---

## BAN List (enforced by detekt rules)

All of these fail the build (`ignoreFailures = false`). Rule names as registered in
`config/detekt/detekt.yml`:

| Pattern | Rule | RuleSet | Why |
|---|---|---|---|
| `stateIn(WhileSubscribed(...))` in VMs with init/drafts | `NoStateIn` | `no-state-in` | Hard to test; keeps upstream active 5s after unsubscribe. Exempts `@OptIn(CombineStateInReadThrough)` |
| `viewModelScope.launch` in production | `NoViewModelScopeInProduction` | `no-viewmodel-scope` | Not injectable; not testable |
| `runBlocking { }` in production | `NoRunBlocking` | `no-run-blocking` | Blocks thread; not testable |
| `factory { SomeViewModel(...) }` in DI | `NoFactoryViewModel` | `no-factory-viewmodel` | Not lifecycle-bound; scope never closed |
| ViewModel without KDoc | `ViewModelMustHaveKDoc` | `kdoc-enforcement` | `AGENTS.md` requires a "why" on every VM |
| `_state.value =` outside `updateState` | `ShadowedState` | `mvi-viewmodel` | Single state-update entry point |
| scope not registered via `addCloseable` | `VmCloseable` | `mvi-viewmodel` | Leaks the coroutine scope |
| scope not last constructor param | `VmScopePosition` | `mvi-viewmodel` | Koin `get()` ordering |
| Side effect inside `combine`/`flatMapLatest` | N/A (manual) | Error | TOCTOU race, stale closures |
| `emit()` from non-suspend context | N/A (compile error) | Error | `emit` is `protected suspend fun` |
| `scope` not `private val` in MviViewModel | N/A (compile error) | Error | `addCloseable(scope)` in init requires scope as property |

---

## Anti-Patterns to Avoid

### ❌ `combine + stateIn(WhileSubscribed)` used as the default for stateful VMs

```kotlin
// DON'T — hard to test, requires Turbine
val state: StateFlow<X> = combine(flow1, flow2) { a, b -> compute(a, b) }
    .stateIn(scope, SharingStarted.WhileSubscribed(5000), Initial)
```

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

### ❌ Stale closures on `_state`

```kotlin
// DON'T — `view` captured from outside is stale
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
| **`MviViewModel<S, I, E>`** | Default for all VMs with intents and/or events |
| **`DraftState` extracted class** | VM has editable form state with `isDirty` tracking |
| **`combine + stateIn(WhileSubscribed)`** | Pure read-through VM (no init, no intents) — e.g., `AgendaViewModel`. Tests will need Turbine. |
| **Dedicated collector for upstream cache** | VM needs to read from repo AND mutate local state (e.g., `TaskDetailViewModel`, `ProjectDetailViewModel`) |

---

## Concrete VMs Following This Pattern

All VMs in the project extend `MviViewModel` as of 2026-09-25:

| VM | File | Notes |
|---|---|---|
| `TagsViewModel` | `feature/tags/TagsViewModel.kt` | PoC migration — MviViewModel |
| `CalendarSyncViewModel` | `feature/calendar_sync/presentation/CalendarSyncViewModel.kt` | Full MviViewModel migration |
| `CalendarViewModel` | `feature/calendar/presentation/viewmodel/CalendarViewModel.kt` | Full MviViewModel migration |
| `AuthViewModel` | `feature/auth/AuthViewModel.kt` | Full MviViewModel migration |
| `BackupViewModel` | `feature/backup/BackupViewModel.kt` | Full MviViewModel migration |
| `TaskDetailViewModel` | `feature/tasks/presentation/viewmodel/TaskDetailViewModel.kt` | Full MviViewModel migration; dedicated cache collector |
| `ProjectDetailViewModel` | `feature/projects/presentation/viewmodel/ProjectDetailViewModel.kt` | Full MviViewModel migration; dedicated cache collector |
| `ProjectsViewModel` | `feature/projects/presentation/viewmodel/ProjectsViewModel.kt` | Full MviViewModel migration |
| `NoteEditor` | `feature/notes/presentation/viewmodel/NoteEditor.kt` | MviViewModel migration (класс называется `NoteEditor`) |
| `NotePreview` | `feature/notes/presentation/viewmodel/NotePreview.kt` | MviViewModel migration (класс `NotePreview`) |
| `AgendaViewModel` | `feature/agenda/presentation/viewmodel/AgendaViewModel.kt` | Pure read-through → `stateIn(WhileSubscribed)` — legitimate exception |

---

## `advanceUntilIdle()` vs `delay()` — The Real Story

The canonical test pattern uses `advanceUntilIdle()` from `kotlinx.coroutines.test`. This works **only when the test dispatcher controls all coroutines in the chain**. In practice:

**`advanceUntilIdle()` works when:**
- The VM uses `FakeProfileAwareCurrentUser(dispatcher = testDispatcher)` — the user's internal `combine` collector is on the test dispatcher
- All `Fake*Repository` instances share the same `ProfileAwareCurrentUser` (Rule 1 of `singularity-todo-test-flaky-prevention`)
- No `Dispatchers.Default` is in the chain

**`delay(ms)` is still needed when:**
- The VM creates `CreateTaskUseCase` which internally uses `FakeProfileAwareCurrentUser` with `Dispatchers.Default` (the dispatcher is not propagated through the use case constructor)
- External I/O (HTTP, filesystem) is involved
- The test uses a stub `CompleteRecurringTaskUseCase` that runs on `Dispatchers.Default`

```kotlin
// In TaskDetailViewModelTest — CreateTaskUseCase creates its own FakeProfileAwareCurrentUser
// on Dispatchers.Default internally. This is why delay() is still used:
@Test
fun `ToggleComplete sets completedAt in repository`() = runTest {
    val vm = createVm(backgroundScope, task.id)
    delay(100) // Let initial subscription establish

    vm.onIntent(TaskDetailIntent.Domain.ToggleComplete)
    delay(50) // scope.launch { mutate(...) } executes immediately

    assertNotNull(fakeTaskRepo.tasks.value["t1"]?.completedAt)
}
```

**The PR-3.1 fix** (`FakeProfileAwareCurrentUser` accepts `CoroutineDispatcher`) enables `advanceUntilIdle()` for VMs that explicitly pass the dispatcher. For VMs that create use cases internally, `delay()` remains necessary until the use case constructors also accept a dispatcher.

**Decision rule:** If `advanceUntilIdle()` doesn't drain the VM's state to completion, use `delay()` with a comment explaining why. Do NOT increase the delay arbitrarily — if you need more than 500ms, the test architecture needs fixing.

---

## See Also

- **`singularity-todo-mvi-framework`** — full framework reference (MviViewModel, EventBus, IntentActions, DraftState)
- **`singularity-todo-vm-pattern-overview`** — router: index to all VM skills
- `singularity-todo-vm-migration-playbook` — migrating old `stateIn` VMs to MviViewModel
- `singularity-todo-test-helpers` — `testScope(...)` helper, three test shapes, `awaitState`, `@ParameterizedTest`
- `singularity-todo-test-flaky-prevention` — 3 rules to prevent flaky VM tests
- `singularity-todo-koin-dsl` — canonical Koin 4.x DSL: `viewModelOf` vs `viewModel {}`, `koinViewModel` vs `koinInject`
- `singularity-todo-feature-scaffold` — canonical 7-file feature template
- `docs/decisions/2026-09-25-local-mvi-framework.md` — ADR

---
name: singularity-todo-test-helpers
description: Standardized test helpers and patterns for ViewModel tests in this project. Covers awaitState, testVm, TestVmContext, assertIs, testScope, FakeRepositories setup, and the three test shapes (smoke, intent→state, regression). Updated with virtual-time await and runVmTest factory from the v3 audit.
---

# VM Test Helpers

This skill documents the standardized helpers for writing ViewModel tests. Use these patterns consistently — they reduce boilerplate and make test failures obvious rather than flaky.

**For the canonical VM constructor shape**, see `singularity-todo-testable-vm`.
**For migration from the old `scopeOverride` pattern**, see `singularity-todo-vm-migration-playbook`.

---

## Available Helpers

All helpers live in `shared/src/jvmTest/kotlin/com/singularity/todo/test/helpers/`:

| File | Helper | Purpose |
|---|---|---|
| `AwaitState.kt` | `TestScope.awaitState(timeoutMs, predicate)` | Wait for virtual-time condition |
| `RunVmTest.kt` | `TestScope.testVm(stateAccessor, factory)` | Factory creating `TestVmContext` |
| `TestVmInfrastructure.kt` | `testVmContext(vm, stateOf, scope)` | Manual `TestVmContext` builder |
| `TestAssertions.kt` | `StateFlow<*>.assertIs<T> { predicate }` | Type-safe assertion on current state |

The `testScope(CoroutineScope)` wrapper lives in `core/coroutines/testScope.kt` (commonMain — available to all test source sets).

---

## The `testScope` Wrapper

`testScope` wraps a `CoroutineScope` (or `CoroutineContext`) in `AutoCloseableCoroutineScope` so it matches the production VM constructor signature:

```kotlin
import com.singularity.todo.core.coroutines.testScope

@Test
fun example() = runTest {
    // Pass TestScope wrapped in AutoCloseableCoroutineScope
    val vm = MyViewModel(deps, scope = testScope(this))

    advanceUntilIdle()
    assertIs<MyUiState.Content>(vm.state.value)
}
```

**Rule**: always wrap `this` (the `runTest` receiver) with `testScope(this)` before passing to a VM. Never pass `backgroundScope` as the VM's scope — reserve it for long-lived helper coroutines in the test infrastructure.

---

## `awaitState` — Virtual-Time Waiting

When a condition can't be asserted immediately after `advanceUntilIdle()` (e.g., debounce, retry delay, polling), use `awaitState`:

```kotlin
import com.singularity.todo.test.helpers.awaitState

@Test
fun titleChanges_areDebounced() = runTest {
    val vm = createVm(...)
    advanceUntilIdle()

    vm.onIntent(TaskDetailIntent.TitleChanged("New"))
    vm.onIntent(TaskDetailIntent.TitleChanged("Newer"))

    // Wait for debounce to fire (uses virtual time — fast)
    awaitState { vm.state.value is TaskDetailUiState.Content }
}
```

**How it works**: uses `currentTime` from `kotlinx.coroutines.test` + `advanceUntilIdle()` in a loop. No real time passes. Fails with a descriptive message if `timeoutMs` is reached.

---

## `testVm` Factory — Preferred Pattern

For VMs with initialization, the `testVm` factory creates the VM, advances to idle, and returns a `TestVmContext`:

```kotlin
import com.singularity.todo.test.helpers.testVm

@Test
fun settingsToggles() = runTest {
    val deps = SettingsDeps(fakeSettings, fakeCurrentUser, Clock)

    val ctx = testVm(
        stateAccessor = { vm: SettingsViewModel -> vm.state },
    ) {
        SettingsViewModel(deps = deps, scope = testScope(this))
    }

    ctx.act { it.onIntent(SettingsIntent.DarkThemeToggled) }
    ctx.assertIs<SettingsUiState.Content>()
}
```

`TestVmContext` provides:
- `act { vm.onIntent(...) }` — executes action then `advanceUntilIdle()`
- `assertIs<T>()` — asserts current state is type `T`
- `assert { predicate }` — asserts predicate on current state

---

## Manual `TestVmContext` Construction

If `testVm` doesn't fit (e.g., you need intermediate `advanceUntilIdle()` calls before acting):

```kotlin
import com.singularity.todo.test.helpers.testVmContext

@Test
fun intermediateState() = runTest {
    val vm = SettingsViewModel(deps, scope = testScope(this))

    // Don't advance yet — check initial Loading state
    val ctx = testVmContext(vm, SettingsViewModel::state, this)
    ctx.assertIs<SettingsUiState.Loading>()

    // Now advance and check Content
    ctx.act { }
    ctx.assertIs<SettingsUiState.Content>()
}
```

---

## `assertIs` Extension

`StateFlow<*>.assertIs<T> { predicate }` asserts the current value is type `T` and optionally checks a predicate:

```kotlin
import com.singularity.todo.test.helpers.assertIs

@Test
fun loaded() = runTest {
    val vm = createVm(...)
    advanceUntilIdle()

    vm.state.assertIs<MyUiState.Content> { it.items.isNotEmpty() }
}
```

---

## FakeRepositories — What's Available

All fakes are in `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeRepositories.kt`.

| Fake | When to use |
|---|---|
| `FakeTaskRepository` | Tasks feature VMs |
| `FakeProjectsRepository` | Projects feature VMs |
| `FakeNotesRepository` | Notes feature VMs |
| `FakeTagsRepository` | Tag-related VMs |
| `FakeSavedAgendaViewsRepository` | Agenda saved views VMs |
| `FakeReminderRepository` | Reminder-related VMs |
| `FakeSettingsRepository` | Settings/access-control VMs |
| `FakeProfileAwareCurrentUser` | All VMs that need userId |
| `FakeAuthRepository` | Auth-related VMs |
| `FakeTextGen` | AI feature VMs — use `FakeTextGen(failureMessage = "...")` for failure scenarios |

**Creating a scoped user ID:**
```kotlin
private val fakeCurrentUser = FakeProfileAwareCurrentUser()
private val userId = fakeCurrentUser.currentUserId
```

**Seeding data synchronously:**
```kotlin
fakeRepo.upsertSync(task.copy(id = TaskId.generate(), title = "Test Task"))
```

### FakeTaskRepository — `recurrence` and `dependsOn`

`FakeTaskRepository` stores `Task` objects directly (bypassing `TaskEntity` → `toTask()` mapping). When you seed a `Task` with `tags` or `dependsOn`, those fields are returned as-is by `observeAll()`:

```kotlin
@Test
fun `observeAll returns tasks with tags and dependsOn`() = runTest {
    val tag1 = TagId.fromString("tag-1")
    val dep1 = TaskId.fromString("dep-1")

    repo.seed(
        Task(..., tags = listOf(tag1), dependsOn = setOf(dep1)),
        Task(..., tags = emptyList()),
    )

    val tasks = repo.observeAll().first()
    val t1 = tasks.first { it.id.value == "t1" }
    assertEquals(listOf(tag1), t1.tags)
    assertEquals(setOf(dep1), t1.dependsOn)
}
```

**Note**: `FakeTaskRepository` does NOT simulate loading `tags`/`dependsOn` from a Room query (the production `userTasksWithExtras` path). `TaskExtrasLoadingTest` validates the production integration. The fake tests the repository contract — what you seed is what you get back.

**For `CompleteRecurringTaskUseCase` tests**: use a **real** `RecurrenceCalculator` — it has no state and is fully deterministic. Only fake the parts that have I/O (the repository):

```kotlin
class CompleteRecurringTaskUseCaseTest {
    private val repo = FakeTaskRepository()
    private val clock = FixedClock(...)          // injected, not static
    private val zone = TimeZone.of("UTC")
    private val calculator = RecurrenceCalculator // real — pure, no fake needed

    private val useCase = CompleteRecurringTaskUseCase(repo, clock, zone, calculator)

    @Test
    fun `FROM_DUE rolls forward to next due date`() = runTest {
        repo.seed(Task(..., recurrence = Interval(FROM_DUE, 1, WEEK), dueDate = d(2026, 1, 15)))

        val result = useCase(taskId)
        assertEquals(d(2026, 1, 22), result.getOrThrow().dueDate)
    }
}
```

---

## Three Test Shapes

### Shape 1: Initial State (Smoke Test)

```kotlin
@Test
fun initialState_isLoading() = runTest {
    val vm = createVm(param1, testScope(this))
    advanceUntilIdle()

    assertIs<MyUiState.Loading>(vm.state.value)
}

@Test
fun afterSeed_dataIsLoaded() = runTest {
    fakeRepo.upsertSync(makeTask(title = "Existing"))

    val vm = createVm(param1, testScope(this))
    advanceUntilIdle()

    val state = vm.state.value
    assertIs<MyUiState.Content>(state)
    assertEquals(1, state.items.size)
}
```

**Use for:** Every new VM — minimal smoke test that the constructor doesn't crash and the initial state is as expected.

### Shape 2: Intent → State Transition

```kotlin
@Test
fun setName_isDirty() = runTest {
    fakeRepo.upsertSync(makeTask())

    val vm = createVm(param1, testScope(this))
    advanceUntilIdle()

    vm.onIntent(FooIntent.SetName("New Name"))

    val state = vm.state.value
    assertTrue((state as? FooUiState.Editing)?.draft?.isDirty == true)
}

@Test
fun save_emitsSavedEvent() = runTest {
    fakeRepo.upsertSync(makeTask())

    val vm = createVm(param1, testScope(this))
    advanceUntilIdle()

    vm.onIntent(FooIntent.Save)
    advanceUntilIdle()

    val event = vm.events.filterIsInstance<FooEvent.Saved>().first()
    assertNotNull(event)
}
```

**Use for:** Feature VMs with intents. Test the intent handler and the resulting state change.

### Shape 3: Regression (Draft Clobbering)

```kotlin
@Test
fun secondUpstreamEmission_doesNotClobberUserDraft() = runTest {
    // Seed with initial task
    fakeRepo.upsertSync(makeTask(id = taskId, title = "Original"))

    val vm = createVm(taskId, testScope(this))
    advanceUntilIdle()

    // User edits the draft
    vm.onIntent(TaskDetailIntent.Domain.SetTitle("User's edit"))
    assertEquals("User's edit", vm._draftTitle.value)

    // Simulate second upstream emission (e.g., reminder tick, pull-to-refresh)
    fakeTaskRepo.emitTask(taskId, makeTask(id = taskId, title = "Remote update"))
    advanceUntilIdle()

    // Draft must remain unchanged — this is the regression test
    assertEquals("User's edit", vm._draftTitle.value)
}
```

**Use for:** VMs with draft/editing state where upstream emissions could overwrite user edits. This is the critical regression test for `TaskDetailViewModel` and `ProjectDetailViewModel` after the side-effects-in-combine fix.

---

## Testing AI Use Cases with FakeTextGen

`FakeTextGen` lives in `test/fakes/FakeRepositories.kt` and supports both success and failure scenarios:

```kotlin
// Success — returns the output string verbatim
val fakeTextGen = FakeTextGen(output = "Summarized: meeting covered Q4 goals.")

// Failure — throws with the given message
val fakeTextGen = FakeTextGen(failureMessage = "Rate limit exceeded")

// Use in a use case test
val tool = SummarizeNoteTool(fakeTextGen)
val useCase = SummarizeNoteUseCase(tool)
val result = useCase("Meeting Notes", "<p>Discussed Q4 goals...</p>")
```

**For ViewModel-level AI action tests**, pass the fake through deps:

```kotlin
@Test
fun summarize_fails_showsError() = runTest {
    val fakeTextGen = FakeTextGen(failureMessage = "Rate limit exceeded")
    val deps = AiDeps(
        textGen = fakeTextGen,
        noteRepository = fakeNotesRepo,
    )
    val vm = NoteEditor(
        deps = deps,
        ai = NoteAiController(
            summarizeNote = summarizeNoteLambda(SummarizeNoteUseCase(SummarizeNoteTool(fakeTextGen))),
        ),
        scope = testScope(this),
    )
    advanceUntilIdle()

    vm.runAiAction(NoteAiAction.Summarize)
    advanceUntilIdle()

    val event = vm.events.filterIsInstance<NotesUiEvent.AiResult>().first()
    assertTrue(event.text.contains("Rate limit"))
}
```

**Available `FakeTextGen` constructors:**
```kotlin
FakeTextGen(output = "some text")                    // success
FakeTextGen(failureMessage = "error")               // failure
FakeTextGen(output = "output", failureMessage = "err") // success unless overridden
```

---

## Standard Test Structure

Every VM test file follows this pattern:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class FooViewModelTest {

    // ─── Fakes ───────────────────────────────────────────────────────────────
    private val fakeRepo = FakeFooRepository()
    private val fakeCurrentUser = FakeProfileAwareCurrentUser()
    private val deps = FooDeps(repo = fakeRepo, currentUser = fakeCurrentUser, clock = Clock)

    // ─── Factory — pass testScope(this) ─────────────────────────────────────
    private fun createVm(
        param1: Type1,
        scope: AutoCloseableCoroutineScope = testScope(this),
    ) = FooViewModel(
        deps = deps,
        param1 = param1,
        scope = scope,
    )
}
```

**The key line:** `scope: AutoCloseableCoroutineScope = testScope(this)` — wraps the test's `TestScope` in `AutoCloseableCoroutineScope` so it matches the production ctor signature.

---

## Test Scope vs backgroundScope — The Rule

```kotlin
@Test
fun example() = runTest {
    // ✅ CORRECT — wrap this (TestScope receiver) in AutoCloseableCoroutineScope
    val vm = createVm(param, testScope(this))

    // ❌ WRONG — backgroundScope has different cancellation lifecycle
    val vm = createVm(param, testScope(backgroundScope))
}
```

`backgroundScope` and `this` (TestScope) share the same `TestDispatcher`, so `advanceUntilIdle()` does flush both. The problem is **lifecycle/cancellation order**: `backgroundScope` outlives the test body, making failures non-deterministic.

**When `backgroundScope` IS appropriate:**
- Long-running fake helpers that should be auto-cancelled at test teardown
- A `FakeFooRepository` that emits on a timer
- Never for the VM itself

---

## `@OptIn` Requirements

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)  // Required for TestScope + runTest
class FooViewModelTest {
    // ...
}
```

This is needed because `runTest` is experimental in older coroutines versions, and `TestScope` as `CoroutineScope` requires `ExperimentalCoroutinesApi`.

---

## Key Files (from v3 audit)

| File | Purpose |
|---|---|
| `shared/src/jvmTest/.../test/helpers/AwaitState.kt` | Virtual-time waiter (pending merge from `refactor/test-standards-v2`) |
| `shared/src/jvmTest/.../test/helpers/RunVmTest.kt` | `testVm` factory (pending merge from `refactor/test-standards-v2`) |
| `shared/src/jvmTest/.../test/helpers/TestVmInfrastructure.kt` | `TestVmContext` + `testVmContext` |
| `shared/src/commonTest/.../test/helpers/TestAssertions.kt` | `StateFlow.assertIs` extension |
| `shared/src/commonMain/.../core/coroutines/testScope.kt` | `testScope` wrapper |

---

## See Also

- `singularity-todo-testable-vm` — canonical VM constructor shape (what the tests test)
- `singularity-todo-vm-migration-playbook` — how to migrate an old VM to the testable shape
- `singularity-todo-vm-intent-pattern` — sealed Intent + onIntent pattern
- `singularity-todo-feature-scaffold` — canonical 7-file feature template with test patterns
- `docs/decisions/2026-09-23-test-standards-enforcement.md` — full ADR documenting all v3 findings

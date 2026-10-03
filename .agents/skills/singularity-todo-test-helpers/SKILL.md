---
name: singularity-todo-test-helpers
description: Standardized test helpers and patterns for ViewModel tests in this project. Covers awaitState, testVm, TestVmContext, assertIs, testScope, FakeRepositories setup, and the three test shapes (smoke, intent→state, regression). Updated with virtual-time await and runVmTest factory from the v3 audit.
---

# VM Test Helpers

This skill documents the standardized helpers for writing ViewModel tests. Use these patterns consistently — they reduce boilerplate and make test failures obvious rather than flaky.

**For the canonical VM constructor shape**, see `singularity-todo-testable-vm`.
**For migration from the old `scopeOverride` pattern**, see `singularity-todo-vm-migration-playbook`.
**For testing `assertCanWrite` (repository write guard)**, see the section below.

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

**Why the backgroundScope ban is load-bearing** (verified and pinned by `TestScopeSemanticsTest`, ADR `2026-09-30-testscope-background-work-semantics`): `advanceUntilIdle()` drains the queue only while *foreground* work is pending. Coroutines on `backgroundScope` run only as a side effect of that pump, or under an explicit `runCurrent()`. A VM collecting on `backgroundScope` therefore never emits its first state under `advanceUntilIdle()` — every assertion reads the initial state, and **rejection tests keep passing against an implementation that does nothing** (a rename that "left the name unchanged" is indistinguishable from a rename that never ran). If a VM test must use `backgroundScope` (e.g. virtual-time debouncing, as in `DraftMviViewModelTest`), drive it with `runCurrent()` or a helper that suspends until the state matches.

**Corollary — the vacuous-rejection trap**: every rejection test ("rename rejects a blank name") must be paired with a success test on the same code path. Alone, a rejection test cannot distinguish "correctly refused" from "never executed".

**The helper already exists — use it.** `testScope(this)` creates the safe shape (foreground context under a child Job, so `close()` cancels the VM's collectors without cancelling `runTest`). Hand-rolling `AutoCloseableCoroutineScope(coroutineContext + Job(...))` duplicates it; hand-rolling `AutoCloseableCoroutineScope(coroutineContext)` without the child Job makes `close()` cancel the TestScope itself and fail `runTest` with `JobCancellationException`.

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

**Creating a scoped user ID:** `FakeProfileAwareCurrentUser()` → access via `.currentUserId`.
**Seeding data:** `fakeRepo.upsertSync(task.copy(id = TaskId.generate()))`.

For `CompleteRecurringTaskUseCase`, fake only the repository — use a real `RecurrenceCalculator` (pure, deterministic).

---

## Three Test Shapes

Every VM test falls into one of three canonical shapes. See the
[singularity-todo-test-shapes](../singularity-todo-test-shapes/SKILL-shapes.md) leaf skill
for the full description, annotated examples, and guidance on choosing the right shape.

| Shape | Purpose |
|---|---|
| Shape 1 — Initial State | Smoke test: VM loads, initial state is correct |
| Shape 2 — Intent → State | Transition: user intent produces correct state change |
| Shape 3 — Regression | Draft clobbering: upstream events do not overwrite user edits |

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

## Testing `assertCanWrite` (Repository Write Guard)

`UserScopedWriteExtTest` lives in `shared/src/commonTest/kotlin/com/singularity/todo/core/repository/`.

```kotlin
class UserScopedWriteExtTest {
    @Test
    fun matchesCurrentUser_doesNotThrow() {
        // Use initialUserId to seed the scoped userId
        val cu = FakeProfileAwareCurrentUser(initialUserId = UserId("u-1"))
        cu.assertCanWrite(entityId = "task-1", entityUserId = UserId("u-1"))
    }

    @Test
    fun anonymousUser_isAccepted() {
        val cu = FakeProfileAwareCurrentUser(initialUserId = UserId("u-1"))
        // UserId.anonymous is always allowed (legacy anonymous entity stamp)
        cu.assertCanWrite(entityId = "task-1", entityUserId = UserId.anonymous)
    }

    @Test
    fun differentUser_throws() {
        val cu = FakeProfileAwareCurrentUser(initialUserId = UserId("u-1"))
        assertFailsWith<CrossUserWriteException> {
            cu.assertCanWrite(entityId = "task-1", entityUserId = UserId("u-2"))
        }
    }
}
```

**Key points:**
- `FakeProfileAwareCurrentUser(initialUserId = ...)` seeds the scoped userId directly
- `assertFailsWith<CrossUserWriteException>` (not `IllegalArgumentException`) — the extension throws `CrossUserWriteException` explicitly, not `require()`
- No `runTest { }` wrapper needed — these are synchronous tests

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

## See Also

- `singularity-todo-testable-vm` — canonical VM constructor shape (what the tests test)
- `singularity-todo-vm-migration-playbook` — how to migrate an old VM to the testable shape
- `singularity-todo-vm-intent-pattern` — sealed Intent + onIntent pattern
- `singularity-todo-feature-scaffold` — canonical 7-file feature template with test patterns
- `docs/decisions/2026-09-23-test-standards-enforcement.md` — full ADR documenting all v3 findings

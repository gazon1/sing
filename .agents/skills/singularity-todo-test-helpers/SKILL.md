---
name: singularity-todo-test-helpers
description: Standardized test helpers and patterns for ViewModel tests in this project. Covers runVmTest extension, FakeRepositories setup, common assert helpers, and the three test shapes (initial state, intent→state, regression).
---

# VM Test Helpers

This skill documents the standardized helpers for writing ViewModel tests after the VM-testability migration. Use these patterns consistently — they reduce boilerplate and make test failures obvious rather than flaky.

**For the canonical VM constructor shape**, see `singularity-todo-testable-vm`.  
**For migration from the old `scopeOverride` pattern**, see `singularity-todo-vm-migration-playbook`.

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

    // ─── Factory — pass test scope directly ──────────────────────────────────
    private fun createVm(
        param1: Type1,
        scope: CoroutineScope = this,   // ← this = TestScope receiver
    ) = FooViewModel(
        deps = deps,
        param1 = param1,
        scope = scope,
    )
}
```

**The key line:** `scope: CoroutineScope = this` — passes the test's `TestScope` (the `runTest` receiver) directly as the VM's scope. No `backgroundScope`, no `scopeOverride`.

---

## The `runVmTest` Extension

For VMs with simple initialization (no complex async setup), use `advanceUntilIdle()` immediately after construction:

```kotlin
@Test
fun initialState_isLoading() = runTest {
    val vm = createVm(param1, this)
    advanceUntilIdle()
    
    val state = vm.state.value
    assertIs<FooUiState.Loading>(state)
}
```

### When you need more control

For VMs with async init that might emit before `advanceUntilIdle()` completes, or for regression tests that need to assert intermediate states:

```kotlin
@Test
fun userEditsDraft_draftIsDirty() = runTest {
    val vm = createVm(param1, this)
    
    // Don't advanceUntilIdle() here — we want to see intermediate states
    vm.onIntent(FooIntent.SetName("Edited"))
    
    val state = vm.state.value
    assertIs<FooUiState.Editing>(state)
    assertTrue(state.draft.isDirty)
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

**Creating a scoped user ID:**
```kotlin
private val fakeCurrentUser = FakeProfileAwareCurrentUser()
private val userId = fakeCurrentUser.currentUserId
```

**Seeding data synchronously (for immediate loading):**
```kotlin
fakeRepo.upsertSync(task.copy(id = TaskId.generate(), title = "Test Task"))
```

---

## Three Test Shapes

### Shape 1: Initial State (Smoke Test)

```kotlin
@Test
fun initialState_isLoading() = runTest {
    val vm = createVm(param1, this)
    advanceUntilIdle()
    
    assertIs<FooUiState.Loading>(vm.state.value)
}

@Test
fun afterSeed_dataIsLoaded() = runTest {
    fakeRepo.upsertSync(makeTask(title = "Existing"))
    
    val vm = createVm(param1, this)
    advanceUntilIdle()
    
    val state = vm.state.value
    assertIs<FooUiState.Content>(state)
    assertEquals(1, state.items.size)
}
```

**Use for:** Every new VM — the minimal smoke test that the constructor doesn't crash and the initial state is as expected.

### Shape 2: Intent → State Transition

```kotlin
@Test
fun setName_isDirty() = runTest {
    fakeRepo.upsertSync(makeTask())
    
    val vm = createVm(param1, this)
    advanceUntilIdle()
    
    vm.onIntent(FooIntent.SetName("New Name"))
    
    val state = vm.state.value
    assertTrue((state as? FooUiState.Editing)?.draft?.isDirty == true)
}

@Test
fun save_emitsSavedEvent() = runTest {
    fakeRepo.upsertSync(makeTask())
    
    val vm = createVm(param1, this)
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
    
    val vm = createVm(taskId, this)
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

**Use for:** VMs with draft/editing state where upstream emissions could overwrite user edits. This is the critical regression test for TaskDetailViewModel and ProjectDetailViewModel after the side-effects-in-combine fix.

---

## Common Assert Helpers

```kotlin
// Truncated list — add as needed
import app.cash.turbine.test
import kotlin.test.assertContains
import kotlin.test.assertIs

// Reusable assertion helpers (add to test file as private functions)
private fun assertLoading(state: FooUiState) = assertIs<FooUiState.Loading>(state)
private fun assertContent(state: FooUiState) = assertIs<FooUiState.Content>(state)
```

---

## Testing Events (SharedFlow)

```kotlin
@Test
fun delete_emitsShowError() = runTest {
    fakeRepo.upsertSync(makeTask())
    
    val vm = createVm(param1, this)
    advanceUntilIdle()
    
    // Trigger error by deleting non-existent
    fakeRepo.deleteSync(TaskId.generate())  // doesn't affect our seeded task
    
    val state = vm.state.value
    // assert on state
}
```

For events that fire once and are consumed:
```kotlin
@Test
fun save_emitsNavigateBack() = runTest {
    val vm = createVm(param1, this)
    advanceUntilIdle()
    
    vm.onIntent(FooIntent.Save)
    
    val event = vm.events.filterIsInstance<FooEvent.NavigateBack>().first()
    assertNotNull(event)
}
```

---

## Test Scope vs backgroundScope — The Rule

```kotlin
@Test
fun example() = runTest {
    // ✅ CORRECT — pass this (TestScope receiver)
    val vm = createVm(param, this)
    
    // ❌ WRONG — backgroundScope has different cancellation lifecycle
    val vm = createVm(param, backgroundScope)
}
```

`backgroundScope` and `this` (TestScope) share the same `TestDispatcher`, so `advanceUntilIdle()` does flush both. The problem is **lifecycle/cancellation order**: `backgroundScope` outlives the test body, making failures non-deterministic. Keep the VM under test on `this` for a single, controlled hierarchy.

**When `backgroundScope` IS appropriate:**
- Long-running fake helpers that should be auto-cancelled at test teardown
- A `FakeFooRepository` that emits on a timer
- Never for the VM itself

---

## @OptIn Requirements

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

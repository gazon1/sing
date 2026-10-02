---
name: singularity-todo-test-shapes
description: The three canonical VM test shapes — smoke, intent→state, and regression (draft clobbering) — with annotated examples and usage guidelines.
---

# Three Test Shapes

These three shapes cover every VM test case. Start with the smoke test for every new VM, then layer in intent→state transitions and the regression shape where applicable.

> **Prerequisite**: `singularity-todo-test-helpers` skill — covers `testScope`, `awaitState`, `testVm`, and FakeRepositories setup. Read that first.

---

## Shape 1: Initial State (Smoke Test)

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

---

## Shape 2: Intent → State Transition

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

---

## Shape 3: Regression (Draft Clobbering)

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

## Choosing the Right Shape

| Situation | Shape |
|---|---|
| New VM, no existing data | Shape 1 — smoke |
| VM with intents, state transitions | Shape 2 — intent→state |
| VM with draft/editing state + upstream emissions | Shape 3 — regression |
| All of the above | All three |

**The vacuous-rejection trap**: every rejection test ("rename rejects a blank name") must be paired with a success test on the same code path. Alone, a rejection test cannot distinguish "correctly refused" from "never executed".

---

## See Also

- `singularity-todo-test-helpers` — `testScope`, `awaitState`, `testVm`, FakeRepositories
- `singularity-todo-testable-vm` — canonical VM constructor shape
- `singularity-todo-vm-migration-playbook` — migrating old VMs to the testable shape

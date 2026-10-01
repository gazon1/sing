---
title: "NoDate regression pinning: contract + VM tests"
date: 2026-10-01
status: accepted
tags: [testing, regression, nodate, agenda, task-repository]
---

# NoDate regression pinning: contract + VM tests

## Context

The oldest open issue in the backlog — "Step 2–4: NoDate tasks not showing" —
was resolved weeks ago by a fix in `ProfileAwareCurrentUser`. The fix itself
was correct, but no regression test existed to pin it. A developer could
accidentally revert the fix and the test suite would still pass.

Two gaps remain:
1. No contract test proves `observeByFilter(All)` returns tasks with `dueDate = null`
   alongside dated ones.
2. No VM-level test proves an undated task seeded before `AgendaViewModel` is
   constructed appears in `Loaded.sections`.

Additionally, `TaskComputed.hasNoDate` uses a deduplication strategy that
could silently lose tasks if `dueDate` is ever non-null but falsy. This is a
refactor concern, not a bug, and is out of scope for this MR.

## Decision

Pin the NoDate fix with two regression tests:

### 1. `TaskRepositoryContractTest` — contract test (jvmTest)

```kotlin
@Test
fun `observeByFilter All returns an undated task alongside dated ones`() = runTest {
    val repo = newRepository(alice)
    repo.create(task("dated-1").copy(dueDate = LocalDate(2026, 1, 1)))
    repo.create(task("undated-1")) // dueDate = null
    advanceUntilIdle()
    val tasks = repo.observeByFilter(TaskFilter.All).first()
    assertEquals(2, tasks.size)
    assertEquals("undated-1", tasks.first { it.id.value == "undated-1" }.id.value)
    assertEquals(null, tasks.first { it.id.value == "undated-1" }.dueDate)
}
```

Runs against both `FakeTaskRepository` (fast, ~100 ms) and the Room implementation
(slow) via JUnit 5 inheritance.

### 2. `AgendaViewModelTest` — VM regression test (commonTest)

```kotlin
@Test
fun `undated task seeded before VM construction is visible in Loaded sections`() = runTest {
    val undated = task(id = "no-date-1", title = "Inbox me") // dueDate = null
    fakeRepo.seed(undated) // seed BEFORE creating the VM

    val vmScope = AutoCloseableCoroutineScope(coroutineContext + Job())
    try {
        val vm = createVm(vmScope)
        runCurrent() // not advanceUntilIdle() — todayFlow is infinite

        val state = assertIs<AgendaUiState.Loaded>(vm.state.value)
        assertTrue(
            state.sections.any { section ->
                section.tasks.any { it.task.id == undated.id }
            },
            "Undated task seeded before VM construction must reach the agenda's No Date section",
        )
    } finally {
        vmScope.close()
    }
}
```

Uses `runCurrent()` instead of `advanceUntilIdle()` because `todayFlow` is an
infinite `while(true)` loop that would cause the test scheduler to hang.

## Rationale

- **Why not test `ProfileAwareCurrentUser` directly?** The class is an internal
  implementation detail. Testing it in isolation would require mocking two
  `StateFlow` sources and would not catch the bug as it manifests in the full
  VM + repository stack.
- **Why `FakeTaskRepository` for seeding?** `seed()` bypasses `create()` and
  inserts directly into the in-memory store, which is the established pattern
  in this codebase for pre-populating test state.
- **Why `testScope(this)` (not `backgroundScope`)?** Per `singularity-todo-testable-vm`,
  the VM scope must be a detached `Job` child of the test scope — the test
  must control when the VM's coroutines are cancelled.

## Consequences

- (a) The NoDate fix in `ProfileAwareCurrentUser._scopedUserId` (synchronous seed
    from two `StateFlow.value` sources) is now pinned by two tests. A revert
    would break at least one of them.
- (b) `TaskComputed.hasNoDate` deduplication strategy is **not** changed. A
    follow-up refactor should clarify whether `hasNoDate` should deduplicate by
    `(taskId, userId)` or by `taskId` alone — the current implementation may lose
    tasks if a task is shared across users. This is tracked separately.
- (c) `deferred-backlog.md` entry `## nodate-steps-2-4` is removed (already
    marked RESOLVED; the regression tests complete the pinning).

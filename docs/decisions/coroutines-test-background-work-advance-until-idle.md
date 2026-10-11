---
title: "advanceUntilIdle skips backgroundScope collectors under coroutines-test 1.11"
date: 2026-10-11
status: accepted
tags: ["testing", "coroutines"]
---

# advanceUntilIdle skips backgroundScope collectors under coroutines-test 1.11

## Context

`TaskDetailCoordinatorGraphTest` builds a coordinator with
`scope = testScope(this.backgroundScope)` and asserted `state is Content` after
`advanceUntilIdle()`. The test failed on CI with the state stuck at `Loading`
(Issue #539) while the identical shape in `TaskDetailCoordinatorTest` passed.

Measured cause, from the kotlinx-coroutines-test 1.11.0 sources:
`TestScope.backgroundScope` adds a `BackgroundWork` context marker, and
`testScope(scope)` copies the parent context, so every collector launched on it is
classified as background. `TestCoroutineScheduler.advanceUntilIdle()` is defined as
"run until only background events remain" (`events.none(TestDispatchEvent::isForeground)`),
so it returns immediately when the only pending work is the coordinator's collectors —
the emission cascade never runs and the assertion fails deterministically.
No exception reaches the `CoroutineExceptionHandler`; the coroutines simply never start.

## Decision

Tests whose subject runs on `testScope(backgroundScope)` must drive it with
`runCurrent()`, not `advanceUntilIdle()`. `runCurrent()` has no foreground filter:
it drains every event scheduled at the current virtual time, including cascades
enqueued during processing. This is the house convention — `TaskDetailCoordinatorTest`
already uses it.

`TaskDetailCoordinatorGraphTest` was fixed accordingly; the trap is recorded here
because the failure mode (silent `Loading`, no exception) looks like a product hang.

## Rationale

`advanceUntilIdle()` is the intuitive "run everything" call, and under
coroutines-test < 1.10 it was. The foreground/background split makes it "run all
*foreground* work", which is exactly wrong for background-injected scopes — the
pattern the canonical VM/corordinator test harness uses. Choosing `runCurrent()`
keeps one convention instead of two, and the initial emission chains (StateFlow
collectors, no debounces on the load path) never need virtual-time advancement.

## Consequences

- Any new coordinator/VM test wired through `testScope(backgroundScope)` must use
  `runCurrent()`; a `Loading`-stuck state with no exception is the signature of
  this trap, not a product bug.
- If a chain ever needs delay-skipping (e.g. debounced edits), use
  `advanceTimeBy(...)` + `runCurrent()`, never bare `advanceUntilIdle()`.
- `testScope` inheriting the full parent context is load-bearing: a foreground
  child scope would make `runTest` fail with UncompletedCoroutinesError on the
  infinite collectors, which is why `backgroundScope` is used at all.

## Links

- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/TaskDetailCoordinatorGraphTest.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/TaskDetailCoordinatorTest.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/coroutines/testScope.kt`
- Issue #539

---
title: "The harness gives the scope"
date: 2026-10-23
status: accepted
tags: [tests, harness, coroutines, conventions]
---

# The harness gives the scope

## Context

The ViewModel test harness handed the test two scopes and let the test choose
the one the ViewModel would live in:

```kotlin
fun <VM, S> TestScope.testVm(
    stateAccessor: (VM) -> StateFlow<S>,
    afterInit: (VM, bgScope: CoroutineScope) -> Unit = { _, _ -> },
    factory: (testScope: TestScope, bgScope: CoroutineScope) -> VM,
): TestVmContext<VM, S>
```

The choice is not the test's to make. The right scope depends on what the
ViewModel collects:

- a ViewModel whose collectors end, or that are suspended on something that
  never arrives, runs on the test's own scope — `advanceUntilIdle` drives it,
  and the state the test asserts on is the state the ViewModel produced;
- a ViewModel whose collector never ends *and keeps producing work* must not
  hold the test open, so it runs on the background scope.

A test that picks the wrong one does not fail where the mistake is. It fails as
a hang, or as a state that never changes, or — worse — as a test that passes
because nothing ran. The attachment-annotation tests carried a comment that
said exactly that: on the background scope the collectors are not driven by
`advanceUntilIdle`, so nothing runs and the state stays `Loading`.

The vocabulary was also scattered. `testScope(this)`, `testScope(bgScope)`,
`AutoCloseableCoroutineScope(ts.coroutineContext + Job())` and
`AutoCloseableCoroutineScope(testScope.coroutineContext)` were four ways of
saying "a scope for the ViewModel", each with its own consequences, and the
test had to know which one it was choosing.

## Idea

The harness owns the scope, and the test names the kind of ViewModel it is
testing.

## Decision

**`VmScope` names the two kinds.**

```kotlin
enum class VmScope { Foreground, Background }
```

**The harness builds the scope and hands it to the factory.**

```kotlin
fun <VM, S> TestScope.testVm(
    stateAccessor: (VM) -> StateFlow<S>,
    vmScope: VmScope = VmScope.Foreground,
    afterInit: (VM, bgScope: CoroutineScope) -> Unit = { _, _ -> },
    factory: (scope: AutoCloseableCoroutineScope) -> VM,
): TestVmContext<VM, S>
```

The factory takes one scope. It does not get the test's scope and the
background scope and decide between them, and it does not build its own.

**`Foreground` hangs off the background scope's job.** The scope keeps the
test's own dispatcher and the test's own background marker — so
`advanceUntilIdle` drives the ViewModel's work — while its job is a child of
the background scope's job. So the ViewModel's work stops when the test ends,
without the test having to remember to cancel anything, and closing the
ViewModel does not cancel the test.

**`Background` is the background scope.** The marker keeps the ViewModel's work
out of `advanceUntilIdle`'s way.

## Rationale

The harness is the place that knows what a scope means. A test that says "this
ViewModel's collector never ends" says something about the ViewModel, and the
scope that follows from it is the harness's business.

The job that hangs off the background scope's job is what makes the two
properties hold at once. The test's own job would make the ViewModel's work the
test's work — `runTest` waits for it, and a collector that never ends hangs the
test at the end. A detached job would leave it running after the test, and the
leak is invisible until the suite slows down. The background scope's job gives
both: the work is not awaited, and it is stopped when the test ends.

The factory taking one scope is the point of the change. A factory that takes
two scopes and passes one of them on is a factory that has been given a choice
it should not have.

## Consequences

- **The call sites name the kind, not the scope.** `vmScope = VmScope.Background`
  says what is true about the ViewModel.
- **The attachment-annotation tests no longer cancel the scope by hand.** The
  harness's scope stops when the test ends.
- **The tests that built their own scope are gone.** Four spellings of "a scope
  for the ViewModel" became two named kinds.
- **A new kind of ViewModel** is a new value of `VmScope`, and every test that
  needs it says so in one word.

## Links

- `shared/src/commonTest/kotlin/com/singularity/todo/test/helpers/RunVmTest.kt`
  — `VmScope` and the harness.
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/attachments/annotation/AttachmentAnnotationViewModelTest.kt`
  — the tests that used to choose their scope.
- `shared/src/commonTest/kotlin/com/singularity/todo/arch/SharedHarnessConventionsTest.kt`
  — the gate on the harness's use.

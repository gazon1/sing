---
title: A test of a fake is not a test of the code
date: 2026-10-04
status: accepted
---

# A test of a fake is not a test of the code

## Context

Three separate things went wrong in one session of sync work, and they have the same
root cause: **something was green that was not testing anything.**

**1. `SyncRepositoryCoalescingTest` tested a copy of a guard, not the guard.**
`FakeSyncRepository.syncOnce()` re-implemented the production re-entry check in
miniature, and the test asserted the miniature. Meanwhile the production guard
(`if (engine.status.value.isRunning())`) had a real hole: the engine resets its status
between the push and pull phases, so a caller landing in that window started a second
cycle. The suite passed the whole time.

**2. `FakeSyncApiClient` ignored its own `sinceLsn` argument.** It returned its whole
scripted list on every call, so any test asserting cursor behaviour was asserting a
property of the fake. The fake was not just unfaithful — it was unfaithful in exactly
the dimension the tests cared about.

**3. `FakeSyncOutboxDao` had no backoff field at all.** It could not express the thing
the production query filters on, so a test could not have caught the missing backoff
even if written.

And separately, a test **I wrote** filled 69 GB of disk in four minutes: it called
`advanceUntilIdle()` on a self-rescheduling delay loop, inside a `TestScope` constructed
in the test body rather than `backgroundScope`. Virtual time ran away, and every
iteration appended its logged failure to the test output buffer.

## Decision

Three rules, each aimed at a different one of those.

**A test that exercises production wiring, not a copy of it.** If a test can only
assert something by re-implementing the logic under test, the assertion is about the
test. `SyncCoordinatorCoalescingTest` drives the real coordinator and counts
concurrent invocations inside the `runCycle` lambda it was handed — the real code path,
observed from outside.

**Fakes must be able to express every dimension the real thing has.** A fake that drops
a parameter of the interface it implements is a lie with a type signature. When a
production query gains a filter, every fake of the DAO must gain it in the same commit —
and the cheapest way to know is that a test would fail otherwise. Here that worked:
`SyncEnginePushTest` failed to compile the moment `getPending` took a clock, which is
exactly the signal wanted.

**A test owns the resources it starts, and it never waits for "idle" on a loop.**
Anything launched in a test body runs in `backgroundScope`; virtual time is advanced by
a bounded amount. Both rules are written into the KDoc of the test that violated them,
with the 69 GB figure, because the reasoning is more useful than the rule.

## Rationale

The three failures are the same failure at three scales. In each case a person (or an
agent) believed a green result meant something it did not, and the gap was invisible
precisely because the signal was present and coloured green. A missing signal is
annoying; a wrong signal is worse, because it is trusted.

The cheapest general defence is unglamorous: make the test assert against the real
object, and make the fake's interface match reality. Both are reviewable in seconds —
"is this test using the production class?" and "does this fake implement every
parameter?" — whereas "does this green mean anything?" is not reviewable at all.

The 69 GB case is the exception that proves the rule from the other side: there the
signal was *loud* (a file the size of a disk) and nobody looked, because the test was
among 1500 and the build was slow for unrelated reasons. Unbounded resource growth in a
test suite is its own kind of critical, and the guard is cheap: bound the time, own the
scope.

## Consequences

- `SyncRepositoryCoalescingTest` is deleted. It cannot be made honest — the contract it
  documented (`Skipped` meaning "someone else is running") no longer exists, and a
  test for a removed contract is worse than no test, because it reads as coverage.
- `SyncOutcome.Skipped`'s KDoc is rewritten. It said "another sync was already running",
  which was true when written and false after `SyncCoordinator` — the kind of drift that
  makes a future agent re-implement the bug in order to satisfy the documentation.
- `FakeSyncApiClient` honours `sinceLsn` and sorts by sequence; `FakeSyncOutboxDao`
  filters on the backoff window; both fakes carry a KDoc saying why.
- `DelayLoopSyncPeriodicTriggerTest` runs its loop in `backgroundScope` and advances
  time in bounded steps, and a dedicated test pins the arithmetic (10 minutes of virtual
  time is exactly two cycles) so a silent switch to real time fails cheaply and loudly
  instead of filling the disk.
- No new framework. Three conventions in existing test files are enough, and every one
  of them is checkable by reading a single test.

## Links

- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/SyncCoordinatorCoalescingTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/DelayLoopSyncPeriodicTriggerTest.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/SyncEngineFakes.kt`
- `shared/src/jvmTest/kotlin/com/singularity/todo/core/sync/TestUtils.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncCoordinator.kt`
- skills: `singularity-todo-testable-vm`, `singularity-todo-test-helpers`,
  `singularity-todo-test-flaky-prevention`

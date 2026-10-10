---
title: "`backgroundScope` collectors are not driven by `advanceUntilIdle()`"
date: 2026-10-08
status: accepted
deciders: agent (investigation), user (review)
---

# ADR: `backgroundScope` collectors are not driven by `advanceUntilIdle()`

## Context

During the sync-attach PR cycle, three independent tests failed with the same
silent symptom: a ViewModel's `StateFlow` stayed at `Loading` even though the
repository had been seeded. The failures appeared to be in unrelated features
(annotations panel, task editor events), which made a shared root cause likely.

The workaround — `testScope(this)` instead of `backgroundScope` — was applied to
12 tests. The question left open was *why* `backgroundScope` fails.

---

## Investigation

The project uses `runTest` from `kotlinx-coroutines-test:1.11.0`:

```kotlin
runTest {          // this: TestScope
    val vm = MyViewModel(get(), scope = backgroundScope)  // ← broken
    advanceUntilIdle()
    // vm.state is still Loading
}
```

Inside `runTest`, `this` is a `TestScope` with its own `TestCoroutineScheduler`.
All `advance*` calls in the test body operate on that scheduler.

`backgroundScope` is created lazily from the outer `Dispatchers.Main` +
**a separate `TestCoroutineScheduler`**. It is exposed as a `CoroutineScope`
(confirmed: compilation error when trying to call `advanceUntilIdle()` on it in
the JVM target). When the test body calls `this.advanceUntilIdle()`, it advances
only the body's scheduler — not `backgroundScope`'s.

This is **not a bug in kotlinx-coroutines-test**. It is the correct behaviour
for a scope that is genuinely on a separate virtual time. The confusion is
purely ergonomic: `backgroundScope` *lives* inside `runTest` but runs on a
different scheduler.

---

## Decision

**Do not use `backgroundScope` as a ViewModel's `scope` parameter in tests.**

Use `testScope(this)` instead, which creates a child `Job` on the test's own
scheduler:

```kotlin
val vmScope = testScope(this)          // child Job on the body's scheduler
val vm = MyViewModel(deps, vmScope)
advanceUntilIdle()                      // drives vmScope's collectors
vmScope.job?.cancel()
```

The child `Job` means cancelling `vmScope` does not cancel the test body, which
is the same guarantee that `backgroundScope` was meant to provide.

The canonical pattern is already documented in `testScope.kt`'s KDoc.

---

## Gate

A detekt rule `VmBackgroundScopeUsage` should eventually forbid passing
`backgroundScope` as any VM's scope parameter. Until that rule is written,
the `testScope()` KDoc is the enforcement surface.

---

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Call `backgroundScope.advanceUntilIdle()` explicitly | Requires importing `TestScope`'s `backgroundScope` extension, conflates two schedulers in one test, and is invisible to future readers |
| Use `runBlocking` | Blocks the thread; deadlocks with any suspending call |
| Change `runTest` scope | Would require patching kotlinx-coroutines-test upstream |

---

## Links

- `BackgroundScopeIsolationTest` — isolation test reproducing the failure
- `testScope.kt:32` — the correct pattern with child `Job`
- `SyncViewModelTest.kt:51` — working example using `testScope(this)`
- `AttachmentAnnotationViewModelTest.kt:85` — fixed with `testScope(this)`

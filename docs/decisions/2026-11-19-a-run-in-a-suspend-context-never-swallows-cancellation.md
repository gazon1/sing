---
title: "A run in a suspend context never swallows cancellation"
date: 2026-11-19
status: open
tags: [detekt, coroutines, tooling]
---

# A run in a suspend context never swallows cancellation

## Context

The custom detekt rule `NoRunCatchingInSuspend` was reworked so that it
decides whether a `runCatching` call sits in a suspend context by walking
*through* inline lambdas rather than stopping at the first call it does not
recognise. A `runCatching` in a suspend function was previously reported only
when the call was lexically inside the function body; after the rework it is
also reported when it sits inside a lambda passed to a call the rule knows is
suspend-bound.

The rule exists for a real reason: `runCatching` catches `Throwable`, so in a
suspend function it swallows `CancellationException`. A cancelled coroutine
that keeps running its body is a coroutine that keeps writing to a scope that
has already been closed, and the symptom shows up somewhere else entirely
later.

The reworked rule immediately found six call sites in `:shared` that predate
it. None of them is in `config/detekt/baseline-shared.xml`, and the baseline
ratchet (`scripts/check-baseline-ratchet.py`) forbids the baseline from
growing, so the finding had to be answered in code.

The six sites:

| file | call |
|---|---|
| `core/sync/HlcStore.kt` | `runCatching { Hlc.parse(it) }` inside `?.let { }` |
| `feature/auth/AuthViewModel.kt` | `runCatching { onFirstSignIn() }` (3 sites) |
| `core/process/ProcessTree.kt` | `runCatching { handle.destroy() }`, `runCatching { handle.destroyForcibly() }` |

Every one of them is a `runCatching` in a suspend context, and every one of
them can swallow the cancellation of the coroutine it runs in.

## Idea

The repository already carries the answer to this exact question:
`com.singularity.todo.core.error.runCatchingCancellable` re-throws
`CancellationException` and propagates `Error` while keeping the
`Result`-returning shape the call sites wanted. The six sites use the plain
`runCatching` because the helper did not exist yet, or because the sites were
written before the rule reached them.

## Decision

**The six call sites use `runCatchingCancellable` instead of `runCatching`.**
No baseline entry is added, and the rule from the rework is not narrowed.

**The helper is used directly, not as a wrapper around `runCatching`.** The
rule's KDoc suggests `runCatchingCancellable { runCatching { … } }`. That
shape satisfies the rule — it flags only calls literally named `runCatching`
— but the inner `runCatching` re-introduces exactly the swallowing the rule
exists to prevent: the outer helper re-throws a `CancellationException` that
the inner `runCatching` has already turned into a `Result.failure`. The
helper already does the whole job, so the inner call is pure loss.

**`Error` keeps propagating.** `runCatchingCancellable` does not swallow
`Error` either, so a `runCatching` that only guarded against an exception
type never needed to catch `Error` in the first place.

## Rationale

The six sites were not rule false positives — but the hazard is not the same at
every site, and claiming it is would overstate two of the three.

- **`AuthViewModel` — the hazard is real.** `onFirstSignIn()` is a suspending
  call, so a cancellation can arrive inside it, and the plain `runCatching`
  swallowed it: the flow continued, the state became `Success`, and the screen
  navigated into an application whose data had never been uploaded. All three
  sites — sign-in, sign-up, attach — are covered by `AuthViewModelTest`, and
  the test fails against the old `runCatching` in exactly the way the rule
  predicts ("a cancelled sign-in reported success: Success").
- **`HlcStore` — the hazard is smaller than it looks.** `Hlc.parse` is total:
  it wraps whatever string it is given and never throws, and it is not a
  suspending call, so no cancellation can arrive *inside the parse*. The
  cancellation this store can actually see is at `dataStore.data.first()`,
  and that was already cancellation-transparent — `catch` does not catch a
  `CancellationException`. The swap therefore changes nothing observable here
  today; it keeps the parse from swallowing a cancellation the day `Hlc.parse`
  stops being total. What a malformed stored value does is unchanged, and is
  written down on `DataStoreHlcStore.load()`: it reads back as a *degenerate*
  clock (`physical` 0, `counter` 0), not as `null`.
- **`ProcessTree` — the swallow was theoretical at these two sites.**
  `ProcessHandle.destroy()` is a blocking Java call that does not throw a
  `CancellationException`, so nothing was being swallowed there. The
  cancellation the tree can actually see is at `awaitExit`'s poll delay, and
  that was never inside a `runCatching` — it propagates, and it did so before
  the swap as well. What the swap does change is the *contract*: a
  cancellation thrown by a handle is now a cancellation rather than an
  ordinary failure, and `ProcessTreeCancellationTest` pins that.

Narrowing the rule instead would have undone a merged, deliberate
improvement and left the six sites exactly as unsafe as they were.

The helper was preferred over an inline `try`/`catch` because it keeps the
`Result` shape the call sites already used, and over the KDoc's wrapper
because the wrapper is strictly weaker.

### What was verified

- `runCatchingCancellable` itself: a cancellation propagates, an ordinary
  exception becomes a `Result.failure`, an `Error` propagates — pinned by
  `RunCatchingCancellableTest` in `commonTest`.
- All three `AuthViewModel` first-sign-in paths: the seed runs on sign-in,
  sign-up and attach, and an ordinary failure of the seed still leaves a
  successful sign-in (the seed is a sync problem, not an auth problem).
- A cancelled seed produces neither `AuthUiState.Success` nor
  `AuthUiEvent.NavigateToHome` — and the negative control (the three sites put
  back on `runCatching`) fails exactly those three tests.
- `HlcStore`: a malformed stored value does not break the settings read, the
  next write still lands, and the next tick still moves forward.
- `ProcessTree`: a `destroy()` that fails does not stop the rest of the tree
  from being ended, the survivor is still killed, the deepest descendant goes
  first, and a cancellation while waiting for the tree to die propagates out
  of `close()` instead of being reported as a normal return.

## Consequences

- `:shared:detekt` is green with no new baseline entries, and the baseline
  ratchet stays satisfied (the baseline did not grow).
- Six call sites now propagate `CancellationException` instead of swallowing
  it. The behaviour change is the point of the rule; the affected paths are
  the parse of a stored clock, the first-sign-in flow, and process-handle
  destruction.
- The rule's KDoc still suggests the wrapper shape. That suggestion is
  weaker than the direct call and is not what this repository does.

## Links

- `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/NoRunCatchingInSuspend.kt`
  — the rule
- `shared/src/commonMain/kotlin/com/singularity/todo/core/error/RunCatching.kt`
  — `runCatchingCancellable`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/sync/HlcStore.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/auth/AuthViewModel.kt`
- `shared/src/jvmMain/kotlin/com/singularity/todo/core/process/ProcessTree.kt`
- `config/detekt/baseline-shared.xml` — unchanged
- `scripts/check-baseline-ratchet.py` — the rule that forbids growing it

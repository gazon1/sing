---
type: decision
date: 2026-10-03
status: accepted
deciders:
  - Singularity Developer
context:
  - "2026-10-03 runCatching investigation: 46 catch(Throwable/Exception) sites, 16 inside suspend functions"
  - "2026-10-03 plan: three-layer fix (cancellation-safe runCatching, transactional outbox, reconciler)"
references:
  - "2026-09-18-mutation-result-handling.md"
  - "core/error/AppError.kt"
  - "core/ui/MviViewModel.kt:127-133"
  - "core/sync/SyncEngine.kt:211,260"
---

# Cancellable result capture

## Context

`kotlin.runCatching` catches `Throwable` — including `CancellationException`. In a
suspend function this is lethal: `CancellationException` must propagate to abort the
calling coroutine; swallowing it makes the coroutine silently continue past its
cancellation boundary, corrupting state.

46 `catch (e: Throwable/Exception)` sites were found across the codebase. 16 of them
wrap suspend work — the highest risk category.

The same `Throwable` catch also swallows `Error` (OOM, StackOverflow), which is never
the right behaviour.

## Decision

Introduce `runCatchingCancellable` in `core/error/RunCatching.kt`:

```kotlin
@OptIn(ExperimentalContracts::class)
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

`CancellationException` re-thrown; `Exception` caught (covers `RuntimeException`,
`AppError`, `IllegalStateException`, etc.); `Error` propagates (OOM, StackOverflow,
`AssertionError` — never silently swallowed).

Replace all 16 HIGH-risk `catch (e: Throwable)` / `catch (e: Exception)` inside
`suspend` functions with the guard-before-catch pattern or `runCatchingCancellable`.

Replace `runCatchingResult` (`AppError.kt:12`) to delegate to `runCatchingCancellable`,
fixing all downstream callers automatically.

Replace `MviViewModel.catchTo` (`MviViewModel.kt:129`) internal `runCatching` with
`runCatchingCancellable` — `CancellationException` thrown by the block must not be
silenced inside `vmScope.launch`.

### What `runCatchingCancellable` does NOT do

It does NOT replace `Flow.catch { }` in ViewModels. Those are a separate layer: a
flow collector's `catch` operator handles upstream errors in the collection pipeline
(an exception escaping a `map { }` inside the flow). `runCatchingCancellable` handles
the throw path from a suspending block inside a `vmScope.launch`. Both layers are
independent and both need fixing.

## Rationale

`runCatching` was designed for non-cancellable contexts. In suspend functions,
`CancellationException` is not a failure — it is the cancellation mechanism. Catching
it and returning a `Result.failure` makes the calling code believe the operation
failed, when it was actually cancelled. The coroutine then continues with
incorrect assumptions about the operation's outcome (e.g. a task that was cancelled
mid-save looks "saved" to the caller).

The explicit `catch (e: CancellationException) { throw e }` guard before `catch (e: Exception)`
is verbose but unambiguous and requires no additional API. `Error` propagation is a
free side-effect: OOM and StackOverflow are never recoverable and should never be
caught.

`runCatchingResult` swallowing `CancellationException` into `AppError.Unknown` was
the most widespread silent corruption path — `SyncEngine.enqueue`, `AuthRepository`,
and all tag operations used it.

## Consequences

- `DraftMviViewModel` and `SyncViewModel` tests that cancel `vmScope` during
  `emitError` will change behaviour: cancellation now throws from `catchTo` instead
  of producing an error event. Tests checking for error-state on cancellation must
  be updated.
- `NotePreviewTest.rapid_Load_calls_cancel_previous_job` must assert state
  content, not just absence of exception.
- The 18 LOW-risk sites (parsers, reflection, `Desktop.browse`) are not changed:
  `CancellationException` is unreachable in those contexts.
- Phase 2 (`NoSwallowedCancellation` detekt rule) enforces this invariant for new code.

## Links

- [kotlin.runCatching docs](https://kotlinlang.org/api/latest/kotlin.coroutines/kotlin.coroutines.run-catching/)
- [Structured concurrency — cancellation propagation](https://kotlinlang.org/docs/cancellation-and-timeouts.html)
- `2026-09-18-mutation-result-handling.md` — established that wide catch is not allowed

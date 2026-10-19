---
created: 2026-10-19
title: "catchTo is fire-and-forget — a state lifecycle must live in one coroutine"
tags: [coroutines, viewmodel, mvi, ui, agenda, testing]
status: accepted
---

# ADR: `catchTo` is fire-and-forget — a state lifecycle must live in one coroutine

## Context

`MviViewModel.catchTo` is the house primitive for "run this, route a failure somewhere":

```kotlin
protected fun catchTo(
    errorLabel: String,
    onError: suspend (String) -> Unit,
    block: suspend () -> Result<*>,
): Job = vmScope.launch { ... }
```

It **launches its own coroutine and returns a `Job`**. The caller does not wait for
it, and — this is the part that bites — the caller cannot know when the work it
wrapped actually finished.

`SavedAgendaViewModel.onSave` was written as two nested coroutines:

```kotlin
scope.launch {                       // outer
    setState(current.copy(isSaving = true))
    scope.launch {                   // inner
        catchTo("Failed to save agenda view", { msg -> emit(ShowError(msg)) }) {
            deps.repo.upsert(updated).onSuccess { emit(SaveSuccess) }
        }
    }
}
```

The inner `catchTo` launches a *third* coroutine and returns immediately. So the
outer coroutine — the one that owns `isSaving` — completed while the write was still
in flight.

Two failures came out of this:

1. **A `finally` that reset `isSaving` reset it too early.** An earlier attempt to
   fix the stuck spinner wrapped the outer `scope.launch` in `try/finally`. Because
   the outer coroutine finished as soon as `catchTo` returned, `isSaving` was
   cleared while the repository write was still parked. The test
   `SavedAgendaViewModelTest.anEditDuringAnInFlightSaveDoesNotReEnableTheSaveButton`
   caught it — the Save button re-enabled mid-write, so the next tap wrote twice.
   The fix was not the `finally`; the fix was the coroutine structure.

2. **A failure before the `catchTo` was silent.** The section payload is built by
   serializing the draft, and that happened *outside* the guarded region. When
   serialization threw, the exception escaped the outer coroutine, reached only the
   crash reporting port, and left the screen spinning forever — no write, no
   navigation, no message. In the test graph the crash port is a no-op, so the
   failure was completely invisible.

## Decision

1. **One coroutine per user action, and the state lifecycle belongs to it.** A save
   that sets `isSaving = true` must do the work and clear the flag **in the same
   coroutine**. The flag is that coroutine's lifecycle.

   ```kotlin
   scope.launch {
       try {
           // build payload, write, emit the outcome
       } catch (e: CancellationException) {
           throw e
       } catch (e: Throwable) {
           crashReporter.report(error = e, issueKey = "…")
           emit(ShowError(e.toMessage("Failed to save agenda view")))
       } finally {
           val latest = currentState          // not the pre-write snapshot
           if (latest is SavedAgendaViewState.Editing) {
               setState(latest.copy(isSaving = false))
           }
       }
   }
   ```

2. **Everything that can throw is inside the guarded region** — payload building and
   serialization included, not only the repository call. A user action that cannot
   happen must say so.

3. **The reset reads the current state, not a snapshot taken before the write.** The
   user may have kept typing while the write was in flight; restoring the old
   snapshot would throw those edits away.

4. **`catchTo` stays the right tool for "run this and report a failure"** — it is
   exactly that, and it is used widely. It is the wrong tool when the caller needs
   to know *when* the work finished, or needs to bracket it with state changes.

## Consequences

- **Positive:** a save that fails shows an error instead of spinning forever; the
  Save button is re-enabled after a failure, so the user can retry.
- **Positive:** the in-flight guard the existing test pins is now structural — one
  coroutine means the flag cannot be cleared before the write ends.
- **Negative:** the save path no longer reports through `catchTo`, so its failure
  message is composed by hand (`toMessage`) and the crash report's issue key is
  written at the call site.
- **Alternative explored — `catchTo` with a `finally` inside its `block`:** the reset
  would then be correctly timed, but the `block` only covers the repository call, so
  the serialization would still sit outside the guard.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/MviViewModel.kt` — `catchTo`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/viewmodel/SavedAgendaViewModel.kt` — `onSave`
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/agenda/SavedAgendaViewModelTest.kt` — `anEditDuringAnInFlightSaveDoesNotReEnableTheSaveButton`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/agenda/SavedAgendaCreateFlowTest.kt` — the flow that was stuck
- `docs/decisions/2026-10-03-kotlinx-coroutines-debug.md` — coroutine diagnostics

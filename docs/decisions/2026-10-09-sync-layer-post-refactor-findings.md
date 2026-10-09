---
status: accepted
date: 2026-10-09
deciders: mavis
---

# Sync layer post-refactor findings (2026-10-09)

## Context

After completing the Stage 4–11 sync engine refactor (PushPhase/PullPhase extraction, D1–D4 fixes, NoCoroutineLaunchInInit rule, SyncOutcome contract migration), a systematic audit of the sync layer and core revealed the following issues. All BUGs have been fixed; ARCH/DEBT items are deferred.

---

## ✅ Fixed — Finding 1: PhaseResult.toResult() unsafe cast

**File:** `core/sync/SyncPhaseReporter.kt:256–260`

The `as Result<T>` cast was removed by specialising the receiver to `PhaseResult<PushSummary>`:

```kotlin
// Before (unsafe)
internal fun <T> PhaseResult<T>.toResult(): Result<T> = when (this) {
    ...
    is PhaseResult.Superseded -> Result.success(PushSummary(...)) as Result<T>  // ← crash
}

// After (safe)
internal fun PhaseResult<PushSummary>.toResult(): Result<PushSummary> = when (this) {
    ...
    is PhaseResult.Superseded -> Result.success(PushSummary(0, 0, 0, superseded = 0))
}
```

This is backward-compatible: the only caller (`SyncEngine.push()`) already passes `PhaseResult<PushSummary>`.

---

## ✅ Fixed — Finding 2: Unreachable NotRun arms with !! crash risk

**File:** `core/sync/SyncEngine.kt:456–467`

Both `NotRun` arms were genuinely unreachable but carried `exceptionOrNull()!!` which would NPE if a future refactor inadvertently called those paths. Fixed by:
1. Replacing `!!` with direct property access (`pushResult.error`, `pullResult.error`) — eliminates the NPE
2. Adding `@Suppress("UNREACHABLE_CODE")` — makes the intentional suppression explicit

```kotlin
val push = when (pushResult) {
    is PhaseResult.Ok -> Result.success(pushResult.getOrThrow())
    is PhaseResult.NotRun -> @Suppress("UNREACHABLE_CODE") Result.failure(IllegalStateException(...))
    is PhaseResult.Failed -> Result.failure(pushResult.error)   // no !! needed
    is PhaseResult.Superseded -> Result.success(PushSummary(...))
}
```

---

## ✅ Fixed — Finding 3: SyncBootstrapper protocol version → Applied (should be Skipped)

**File:** `core/sync/SyncBootstrapper.kt:65–69`

When a pulled event had a protocol version newer than this client supports, `ApplyOutcome.Applied` was returned — cursor advanced and event counted as "applied", but no local state changed. Fixed to return `ApplyOutcome.Skipped(...)` with a descriptive reason, so monitoring can distinguish "processed" from "skipped due to version".

---

## Deferred — Finding 4: SyncEngine singleton never closed (ARCH) — [#260](https://github.com/gazon1/sing/issues/260)

`SyncEngine` implements `AutoCloseable by scope` and launches a session-watching coroutine in `init`. It is a Koin `single {}` with no `close()` call in the codebase. The coroutine lives for process lifetime. Acceptable for current requirements; revisit if account-reset without process death becomes a requirement.

---

## Deferred — Finding 5: PhaseResult.NotRun isFailure = true (ARCH) — [#255](https://github.com/gazon1/sing/issues/255)

`PhaseResult.NotRun.isFailure() = true` is semantically wrong. "Did not run" is not "failed". This was partially addressed in Finding 2 by suppressing the `toResult()` arm, but the underlying `PhaseResult` contract remains incorrect. Deferred as a separate small migration.

---

## Deferred — Finding 6: SyncOutcome.Completed is too permissive (ARCH) — [#256](https://github.com/gazon1/sing/issues/256)

```kotlin
data class Completed(val push: Result<PushSummary>, val pull: Result<PullSummary>) : SyncOutcome
```

`Result<*>` permits a `NotRun`-derived failure inside a "completed" sync. Deferred.

---

## Deferred — Finding 7: SyncEngineState.phases exposed as internal val (ARCH) — [#257](https://github.com/gazon1/sing/issues/257)

`SyncEngineState.phases` (`SyncPhaseReporter`) is passed to `PushPhase`/`PullPhase` but is `internal val`, not `private`. Future code in the module could mutate phase state directly. Should be narrowed to only the methods each phase needs.

---

## Deferred — Finding 8: SeedPlanner/SyncBootstrapper core→feature dependency (ARCH) — [#258](https://github.com/gazon1/sing/issues/258)

Both classes in `core/sync/` take feature repository parameters (`TaskRepository`, `NotesRepository`, etc.). Violates stated layering rule but is intentional and documented. Koin compiler cannot verify the registration-order dependency. Deferred; consider a `SyncEntityRegistry` port.

---

## Deferred — Finding 9: SyncScopeProvider interface location (ARCH) — [#261](https://github.com/gazon1/sing/issues/261)

The interface lives in `core/sync` but its sole implementation is in `feature/profile`. Runtime-safe; import dependency runs `core → feature` at the type level. Deferred.

---

## Deferred — Finding 10: SyncPrefs init silent fallback (DEBT) — [#262](https://github.com/gazon1/sing/issues/262)

`SyncPrefs.init` ignores `DataStore` read failures and falls back to defaults silently. Impact is low (writes are always atomic); logged as debt.

---

## Finding 11 — Zero unit tests for sync components (TEST) — [#259](https://github.com/gazon1/sing/issues/259)

Eight core sync components have 0% test coverage: `SyncEngine`, `SyncCoordinator`, `PushPhase`, `PullPhase`, `SyncBootstrapper`, `SyncRunner`, `SyncEngineState`, `HandlerRegistry`. The D1/D2 fixes and the channel coalescing in `SyncCoordinator` have no automated verification.

Priority order for tests: `HandlerRegistry` → `SyncEngineState` → `SyncCoordinator` → `PushPhase` → `PullPhase` → `SyncEngine`.



The following were investigated and found to be correct:

| Check | Result |
|-------|--------|
| No `stateIn` in ViewModels | ✅ Zero occurrences in `commonMain` |
| No `runBlocking` in production | ✅ Zero in ViewModels |
| `SyncEngine.init` scope.launch | ✅ Grandfathered; session watcher must start with engine |
| `HandlerRegistry.registerHandler` non-suspend | ✅ `MutableStateFlow` assignment is atomic |
| D4 fix in `enqueue` | ✅ All 3 DAO sites wrapped in `localStorage {}` |
| D1 fix: `patch == null` | ✅ `PushPhase.kt:129` → `superseded++` |
| D2 fix: `activeScope` captured | ✅ Passed to `deferOrDeadLetter`, not re-read |
| `SyncEngineState` encapsulation | ✅ Private `MutableStateFlow`s, public `StateFlow`s |
| `SyncCoordinator` channel coalescing | ✅ Conflated channel, single consumer |
| `SyncRunner.scopeRef` | ✅ `val` reference, never reassigned |

---

## Actions Summary

| # | Status | GH | Priority | Type | Action |
|---|--------|----|----------|------|--------|
| 1 | ✅ fixed | — | — | BUG | Specialise `PhaseResult.toResult()` to `PhaseResult<PushSummary>` |
| 2 | ✅ fixed | — | — | BUG | Replace `!!` with direct `.error`; add `@Suppress` on unreachable arms |
| 3 | ✅ fixed | — | — | BUG | Return `Skipped` instead of `Applied` for unknown protocol version |
| 4 | deferred | [#260](https://github.com/gazon1/sing/issues/260) | low | ARCH | SyncEngine singleton lifecycle |
| 5 | deferred | [#255](https://github.com/gazon1/sing/issues/255) | low | ARCH | PhaseResult.NotRun `isFailure = true` |
| 6 | deferred | [#256](https://github.com/gazon1/sing/issues/256) | low | ARCH | SyncOutcome.Completed type safety |
| 7 | deferred | [#257](https://github.com/gazon1/sing/issues/257) | low | ARCH | Narrow `SyncEngineState.phases` exposure |
| 8 | deferred | [#258](https://github.com/gazon1/sing/issues/258) | low | ARCH | Extract `SyncEntityRegistry` port |
| 9 | deferred | [#261](https://github.com/gazon1/sing/issues/261) | low | ARCH | Move `SyncScopeProvider` to `core/profile` |
| 10 | deferred | [#262](https://github.com/gazon1/sing/issues/262) | very low | DEBT | Log warning on `SyncPrefs` DataStore init failure |
| 11 | deferred | [#259](https://github.com/gazon1/sing/issues/259) | high | TEST | Add unit tests for sync components |

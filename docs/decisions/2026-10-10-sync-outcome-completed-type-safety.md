---
status: proposed
date: 2026-10-10
deciders: mavis
gh: https://github.com/gazon1/sing/issues/256
---

# SyncOutcome.Completed is too permissive [#256](https://github.com/gazon1/sing/issues/256)

## Context

`syncOnce()` returns `SyncOutcome` — a sealed interface with three arms:

```kotlin
sealed interface SyncOutcome {
    data class Completed(
        val push: Result<PushSummary>,   // ← allows NotRun-derived failure
        val pull: Result<PullSummary>,    // ← allows NotRun-derived failure
    ) : SyncOutcome
    data class CouldNotStart(val error: AppError) : SyncOutcome
    data object NothingToDo : SyncOutcome
}
```

After [#255](./2026-10-09-sync-layer-post-refactor-findings.md) (`NotRun.isFailure() = false`), a `NotRun` maps to `Result.success(PushSummary(0,0,0))`. So `Completed` now contains only genuine results. The remaining concern is the **type-safety story**: `Result<PushSummary>` accepts any `Result`, including a `Result.failure()` from a `PhaseResult.Failed`. A caller cannot distinguish "phase ran and failed" from "phase did not run" without inspecting the inner `Result`.

## Idea

Replace `Result<T>` with a tighter result type that carries explicit phase-exit semantics:

```kotlin
data class Completed(
    val push: PhaseResult<PushSummary>,
    val pull: PhaseResult<PullSummary>,
) : SyncOutcome
```

Callers that currently do `outcome.push.isFailure` would do `outcome.push is Failed` — structurally identical but enforced by the type system rather than convention.

## Decision

**Deferred.** The current `Result<PushSummary>` is now safe after #255 (all four `PhaseResult` variants map to well-defined `Result` outcomes). Changing `SyncOutcome.Completed` would require updating all call sites in `SyncRunner`, `SyncEngineTest`, and any UI layer that observes sync outcomes. The mechanical change is small but touches several layers.

**Alternative considered:** Keep `Result<PushSummary>` but add an extension `val PhaseResult<PushSummary>.result: Result<PushSummary>` so callers convert explicitly. This preserves the sealed result for internal use and avoids a large call-site migration.

## Consequences

- Deferred until a future refactor touches `SyncOutcome` call sites for another reason.
- The inner `Result` is currently inspected at `SyncRunner.kt:126` (`outcome.push.isFailure`) — a single call site, easy to migrate.

## Links

- [#255](./2026-10-09-sync-layer-post-refactor-findings.md) — NotRun.isFailure fix
- `SyncEngine.kt:452–484` — `syncOnce()` implementation
- `SyncRunner.kt` — sole consumer of `SyncOutcome`

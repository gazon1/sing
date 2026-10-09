---
status: proposed
date: 2026-10-10
deciders: mavis
gh: https://github.com/gazon1/sing/issues/257
---

# SyncEngineState.phases exposed as internal val [#257](https://github.com/gazon1/sing/issues/257)

## Context

`SyncEngineState` (in `SyncPhaseReporter.kt`) holds mutable phase reporting state:

```kotlin
internal class SyncEngineState(
    log: Logger,
    crashReporter: CrashReportingPort,
) {
    private val _status = MutableStateFlow<SyncEngineStatus>(SyncEngineStatus.Idle)
    val status: StateFlow<SyncEngineStatus> = _status.asStateFlow()

    private val _lastPush = MutableStateFlow<Result<PushSummary>?>(null)
    val lastPush: StateFlow<Result<PushSummary>?> = _lastPush.asStateFlow()

    private val _lastPull = MutableStateFlow<Result<PullSummary>?>(null)
    val lastPull: StateFlow<Result<PullSummary>?> = _lastPull.asStateFlow()

    val phases: SyncPhaseReporter = SyncPhaseReporter(log, crashReporter, ...)
    // ↑ internal, not private — passed to PushPhase and PullPhase
}
```

`SyncPhaseReporter` (`phases`) is `internal val`, not `private`. It is passed to `PushPhase` and `PullPhase` as a constructor argument, giving each phase the ability to call any public method on `SyncPhaseReporter` — including state-mutating ones like `setPushStatus`.

## Idea

Narrow what each phase receives: instead of the full `SyncPhaseReporter`, pass only the specific view each phase needs. For example:

```kotlin
// PushPhase only needs to record results and set push-specific status
class PushPhase(
    ...
    private val phaseHooks: PhaseHooks,  // narrow interface
)

// PullPhase only needs to record results
class PullPhase(
    ...
    private val phaseHooks: PhaseHooks,
)

interface PhaseHooks {
    fun recordPushResult(Result<PushSummary>)
    fun recordPullResult(Result<PullSummary>)
    fun setStatus(SyncEngineStatus)
}
```

## Decision

**Deferred.** The current structure is runtime-safe: `PushPhase` and `PullPhase` are the only two clients of `SyncEngineState.phases`, and they use it correctly. The encapsulation gap is a source-level concern (future code in the module could call `phases.recordPushResult(...)` from a non-push context), not a runtime defect.

Changing this requires defining an interface, updating both phase constructors, and verifying no call sites pass the full `phases` object. Low urgency; deprioritised against shipping sync tests.

## Consequences

- Future code that incorrectly calls `phases.setStatus(Pushing)` from outside a phase would not be caught at compile time. The existing `SyncPhaseReporter` convention (methods named `recordPushResult`, `recordPullResult`) makes misuse obvious without compile-time enforcement.
- The encapsulation improvement is a source-only refactor with no behavioural change, making it a good candidate for a "cleanup" sprint.

## Links

- `SyncPhaseReporter.kt:22–42` — `SyncEngineState` definition
- `SyncPhaseReporter.kt:61–191` — `SyncPhaseReporter` public methods
- `SyncEngine.kt:331–335` — engine's public `StateFlow` surface

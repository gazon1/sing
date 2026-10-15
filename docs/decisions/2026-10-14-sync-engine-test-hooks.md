---
title: "ADR: SyncEngine test hooks for G4/G5 integration tests"
date: 2026-10-14
status: open
tags: [sync, testing, architecture]
description: Engine-level injection points to enable G4 (sign-out mid-cycle) and G5 (scope null during syncOnce) integration tests.
---

# ADR: SyncEngine test hooks for G4/G5 integration tests

## Context

`SyncEngineIntegrationTest.kt` (2026-10-14) covers 4 integration test gaps. G4 and G5 require behaviour that cannot be tested without engine-level hooks:

### G4 — SignOut mid-cycle

```kotlin
syncOnce() starts
  → push phase runs, reads active scope
  → session changes to SignedOut   ← we need to INJECT this
  → pull phase runs
```

The `active` scope was captured at the start of `syncOnce`. The session change in the middle does not affect the push phase. But `PullPhase` needs to handle the signed-out session gracefully. Without an injection point, we cannot simulate a mid-cycle sign-out.

### G5 — Scope null during syncOnce

```kotlin
syncOnce() starts
  → scope read succeeds, active = scope
  → scopeProvider.current emits null   ← we need to INJECT this
  → push phase runs
```

The scope disappearing mid-cycle should not abort the current push (which already read `active`). The scheduler should stop. Without a cancellation or injection hook, we cannot test this.

## Decision

### Hook 1: `onPushCompleted` callback in `SyncEngine.syncOnce`

`onPushCompletedCallback: ((PhaseResult<PushSummary>, SyncScope) -> Unit)?` — a constructor parameter of `SyncEngine` with a `null` default (no-op in production). The callback is invoked synchronously between the push and pull phases, inside the engine's lock.

```kotlin
// SyncEngine.syncOnce() — after push phase, before pull phase
val pushResult = pushPhase.push(active)
onPushCompletedCallback?.invoke(pushResult, active)  // G4 injection point
val pullResult = pullPhase.pull(active, sinceLsn = cursor)
```

**Usage for G4**: Set the callback to change `FakeSyncAuthRepository.set(Session.SignedOut)` — when push completes and the callback fires, the session is changed. Pull phase then runs with the new session.

### Hook 2: `MidFlightScopeNullProvider`

`ThrowingSyncScopeProvider` already exists in `SyncEngineFakes.kt` for simulating scope read failures. For G5, a new `MidFlightScopeNullProvider` was added:

```kotlin
class MidFlightScopeNullProvider(initialScope: SyncScope) : SyncScopeProvider {
    private var readCount = 0
    override val current: Flow<SyncScope?> = flow {
        readCount++
        emit(if (readCount == 1) initialScope else null)
    }
}
```

A test using this in G5 would:
1. `syncOnce()` reads scope successfully → `active = scope`
2. Scope provider switches to `null` after first emission
3. Push phase uses the captured `active` scope
4. Test verifies push succeeded, scheduler stopped

### Helper: `buildTestableSyncEngine`

A factory function in `SyncEngineFakes.kt` that accepts `onPushCompletedCallback` and all other engine dependencies, wiring them into a `SyncEngine` instance:

```kotlin
val engine = buildTestableSyncEngine(
    authRepository = auth,
    onPushCompletedCallback = { _, _ -> auth.set(Session.SignedOut) },
    scopeProvider = MidFlightScopeNullProvider(scope),
    scope = engineScope,
)
engine.syncOnce()  // G4 or G5 scenario runs
```

## Consequences

- **Positive**: Enables G4/G5 integration tests; G5 fakes are in `jvmTest` so they never ship
- **Neutral**: Callback is optional — production code path is unchanged; `null` default means no behaviour change
- **Negative**: A callback slot in production constructor is a mild smell; mitigated by the explicit `?.invoke()` no-op default

## Why not a test-only subclass

The ADR originally considered Option B — a `TestableSyncEngine` subclass that overrides `syncOnce()`. This does not work in this Kotlin module structure:

- `syncOnce` is `internal` in `SyncEngine`, which is defined in `commonMain`
- `jvmTest` is a separate source set / module boundary
- `internal` members are not accessible across this boundary, so `override` is illegal

The callback approach (Option A) is the only viable path. It is a one-line addition to the engine's constructor with a null default, and it achieves the same effect.

## Links

- `SyncEngineIntegrationTest.kt` — issue #628
- `SyncEngine.kt` — engine under test
- `SwitchingSyncScopeProvider` — existing switching scope provider
- `ThrowingSyncScopeProvider` — existing throwing scope provider

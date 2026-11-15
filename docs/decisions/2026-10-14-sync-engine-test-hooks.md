---
title: "ADR: G4/G5 sync scenarios are tested through API and scope fakes"
date: 2026-10-14
status: accepted
tags: [sync, testing, architecture]
description: The G4 (sign-out mid-cycle) and G5 (scope going null during syncOnce) scenarios are integration tests over the engine's existing seams — the fake API and the fake scope provider — with no test callback in the production constructor.
---

# ADR: G4/G5 sync scenarios are tested through API and scope fakes

## Context

`SyncEngineIntegrationTest.kt` (2026-10-14) covers 4 integration test gaps. G4 and G5
need a condition that arrives **while `syncOnce` is running**:

### G4 — SignOut mid-cycle

```kotlin
syncOnce() starts
  → push phase runs, reads active scope
  → session changes to SignedOut   ← we need to INJECT this
  → pull phase runs
```

The `active` scope is captured at the start of `syncOnce`. The session change in the
middle does not affect the push phase, but `PullPhase` has to handle the signed-out
session gracefully.

### G5 — Scope null during syncOnce

```kotlin
syncOnce() starts
  → scope read succeeds, active = scope
  → scopeProvider.current emits null   ← we need to INJECT this
  → push phase runs
```

The scope disappearing mid-cycle must not abort the current push (which already read
`active`), and the scheduler must stop.

## Decision

Both conditions are injected **through the seams the engine already has** — the
`SyncApiClient` and `SyncScopeProvider` dependencies — in `SyncEngineIntegrationTest`,
and no production code carries a test-only path.

### G4 — the fake API signs out while push is in flight

The session is changed from inside the fake's `batchPush`. `PushPhase.push()` reads
the session once when it starts, so the cycle's push finishes with the session and
scope it began with; `PullPhase` reads the session when it starts and sees
`SignedOut`:

```kotlin
val api = object : FakeSyncApiClient() {
    override suspend fun batchPush(request: BatchPushRequest): BatchPushResponse {
        signOutHelper?.invoke()   // authRepository.set(Session.SignedOut)
        return BatchPushResponse(request.patches.map { PatchResult(it.patchId, ok = true) })
    }
}
```

There is no seam between `pushPhase.push()` and `pullPhase.pull()`, so this is the
closest injection the engine has. Push had already read everything the sign-out could
change, so the behaviour is the same as landing it strictly after push returns.

Verified by `sign out during push — push finishes with its session and pull runs
signed out`.

### G5 — the fake scope provider clears the scope mid-cycle

`FakeSyncScopeProvider` is mutable (`set(null)`), and `ThrowingSyncScopeProvider`
already covers a scope read that fails. Clearing the scope from inside `batchPush`, or
between two `syncOnce` calls, reproduces both halves of the scenario:

```kotlin
val api = object : FakeSyncApiClient() {
    override suspend fun batchPush(request: BatchPushRequest): BatchPushResponse {
        scopeProvider.set(null)   // the scope disappears during push
        return BatchPushResponse(request.patches.map { PatchResult(it.patchId, ok = true) })
    }
}
```

Verified by `syncOnce that started with valid scope completes even if scope becomes
null` and `syncOnce with nulling scope mid-cycle — push uses captured scope`.

## Considered, and removed

The first version of this decision put the injection points in the engine itself:

- an `onPushCompletedCallback` constructor parameter, invoked between push and pull;
- a `MidFlightScopeNullProvider` that emits the scope once and `null` afterwards;
- a `buildTestableSyncEngine` factory that wires the callback in.

They were implemented, and then removed: each fake above produces the same condition
without a slot in a production constructor. They are recorded here because the G4/G5
tests were written against them first, and because the removal is the decision.

## Why the scenario was not a test-only subclass

A `TestableSyncEngine` subclass that overrides `syncOnce()` does not work in this
module structure:

- `syncOnce` is `internal` in `SyncEngine`, which is defined in `commonMain`;
- `jvmTest` is a separate source set / module boundary;
- `internal` members are not accessible across this boundary, so `override` is illegal.

That constraint is why the injection was first placed in the constructor. The fakes
made the question moot: `batchPush` and `SyncScopeProvider.current` are both reached
from the engine, so both can be controlled from the test without touching the engine.

## Consequences

- **Positive**: G4 and G5 are integration tests over the real `syncOnce`, and the
  production constructor has no test-only parameter.
- **Positive**: the fakes live in `jvmTest`, so nothing they carry can ship.
- **Neutral**: the G5 scenario depends on `FakeSyncScopeProvider` being mutable; a
  future provider that is not mutable would need its own fake with the same seam.

## Resolution

Resolved 2026-11-15: the G4/G5 scenarios are tested; the hooks this ADR first proposed
are gone.

Verified: `SyncEngineIntegrationTest` covers G4 and G5 as described above.
`onPushCompletedCallback`, `MidFlightScopeNullProvider` and `buildTestableSyncEngine`
no longer exist, and `SyncEngine`'s constructor takes no test callback. The decision —
these two scenarios are covered — stands; the mechanism is the fakes.

## Links

- `SyncEngineIntegrationTest.kt` — issue #628
- `SyncEngine.kt` — engine under test
- `SyncEngineFakes.kt` — `FakeSyncApiClient`, `FakeSyncScopeProvider`,
  `ThrowingSyncScopeProvider`
- `SwitchingSyncScopeProvider` — existing switching scope provider
- `ThrowingSyncScopeProvider` — existing throwing scope provider

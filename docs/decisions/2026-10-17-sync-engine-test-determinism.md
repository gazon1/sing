---
title: "SyncEngine tests: enqueue + advanceUntilIdle is the canonical pattern"
date: 2026-10-17
status: accepted
tags: [sync, testing, coroutines]
---

# SyncEngine tests: `enqueue` + `advanceUntilIdle` is the canonical pattern

## Context

`SyncEngine.syncOnce()` and `SyncEngine.enqueue()` are both `suspend` functions. A
test that calls `e.enqueue(entity)` and immediately calls `e.syncOnce()` faces a
nondeterminism window: if `enqueue` has not yet committed the row to the outbox
database before `syncOnce` scans it, the patch is not sent. The test then fails
with "patch not found in outbox" rather than "sync completed normally".

The same applies to any gap between two `enqueue` calls, or between `enqueue` and
`syncOnce`.

## The failure mode

```kotlin
// ❌ Unreliable: enqueue may not have committed before syncOnce scans
e.enqueue(entity)
val outcome = e.syncOnce()
advanceUntilIdle() // too late: syncOnce already ran with empty outbox
```

`enqueue` calls `OutboxService.enqueue()`, which is a suspend function that writes
to the Room database. `syncOnce` reads the database at the start of the push phase.
If the write is not yet flushed, the scan sees an empty outbox. The test fails
intermittently depending on timing, machine load, and whether the test runs in
isolation or as part of a suite.

## The canonical pattern

```kotlin
// ✅ Reliable: enqueue commits before syncOnce reads
e.enqueue(entity)
advanceUntilIdle()        // flush the write before proceeding
val outcome = e.syncOnce()
advanceUntilIdle()
engineScope.close()
```

`advanceUntilIdle()` after `enqueue` flushes all pending writes in the test scope.
The outbox row is committed before `syncOnce` is called.

The second `advanceUntilIdle()` after `syncOnce` is defensive: it drains any
尾 (tail) emissions from the engine's internal coroutines before the engine scope
is closed.

## Why this is not a test anti-pattern

`advanceUntilIdle()` is the standard kotlinx-coroutines-test tool for draining
pending work. It is not a substitute for a write barrier or a synchronous commit —
it is the correct tool in the test scope. The same pattern is used throughout the
`SyncEngineIntegrationTest`:

```kotlin
// Enqueue, flush, then sync
e.enqueue(TestEntity("t1"))
advanceUntilIdle()

val outcome1 = e.syncOnce()
advanceUntilIdle()
assertTrue((outcome1 as SyncOutcome.Completed).push.isSuccess)

// Enqueue again for the next cycle
e.enqueue(TestEntity("t2"))
advanceUntilIdle()

val outcome2 = e.syncOnce()
advanceUntilIdle()
engineScope.close()
```

## Why not a synchronous enqueue-for-test

An alternative would be a test-only overload of `enqueue` that commits synchronously:

```kotlin
// Hypothetical — rejected
suspend fun SyncEngine.enqueueBlocking(entity: SyncableEntity) {
    enqueue(entity).getOrThrow()
    outboxDao.flushForTest() // extra API just for tests
}
```

This adds API surface to the engine that is test-only and would never be used in
production. It also conflates the test contract (writes are flushed) with the
mechanism (flush API), when the test scope already provides the correct mechanism
via `advanceUntilIdle()`.

The coroutine test library is the right place to express "wait for pending work";
an engine-specific API would duplicate that contract and introduce drift.

## Why parallelism races are non-deterministic by design

Two `syncOnce` calls in parallel on the same engine:

```kotlin
val outcome1 = e.syncOnce()
val outcome2 = e.syncOnce()
advanceUntilIdle()
```

Are inherently non-deterministic. The second call may start before the first reads
the outbox, so it finds an empty outbox and sends zero patches. The test can assert:

```kotlin
// At least one completed — one may have raced with the other
assertTrue(outcome1 is SyncOutcome.Completed || outcome2 is SyncOutcome.Completed)
```

This is the correct assertion because both outcomes are valid. The race is not a
bug — it is the scheduler expressing the same outcome two ways. An assertion that
demands exactly one push call would be wrong.

## Consequence

`SyncEngineIntegrationTest` uses `advanceUntilIdle()` after every `enqueue` call
and after every `syncOnce` call. The parallel-race test uses `assertTrue(outcome1
is SyncOutcome.Completed || outcome2 is SyncOutcome.Completed)` — the only
semantically correct assertion for this scenario.

## Links

- `SyncEngineIntegrationTest.kt` — the test this ADR documents
- `OutboxService.kt` — `enqueue` implementation
- `SyncEngine.kt:433` — `enqueue` suspend function
- `2026-10-14-sync-engine-test-hooks` — G4/G5 test hooks

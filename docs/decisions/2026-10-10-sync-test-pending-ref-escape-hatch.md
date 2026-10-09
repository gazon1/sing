---
status: accepted
date: 2026-10-10
deciders: mavis
---

# Replace SyncApiClient.pendingRef escape hatch with onBeforeResponseLoop callback

## Context

`FakeSyncApiClient` in `TestUtils.kt` exposed a `MutableList` escape hatch (`pendingRef`) for testing the D1 race. This was on the `SyncApiClient` interface itself:

```kotlin
// Old: SyncApi.kt
interface SyncApiClient {
    var pendingRef: MutableList<SyncOutboxEntity>?
}
```

`PushPhase.push()` set `api.pendingRef = pending` before calling `batchPush`, and the test mutated it inside a `batchPush` override.

Three problems with this approach:

1. **Production interface pollution** — `pendingRef` was on `SyncApiClient`, the public interface, even though production code never used it. `SupabaseSyncApiClient` implemented it as a no-op just to satisfy the compiler.
2. **MutableList in PushPlan.Ready** — `pending: MutableList<SyncOutboxEntity>` weakened an immutability guarantee. The plan should be an immutable snapshot.
3. **Fragile test** — the test had to override `batchPush` to intercept and mutate `pendingRef`. This tightly coupled the test to the implementation detail of where `pending` lived.

## Decision

Replace `pendingRef` with `onBeforeResponseLoop` — a named callback on `SyncApiClient` that fires after `batchPush` returns, before the response loop:

```kotlin
// New: SyncApi.kt
interface SyncApiClient {
    var onBeforeResponseLoop: (
        pending: MutableList<SyncOutboxEntity>,
        response: BatchPushResponse,
    ) -> Unit

    suspend fun batchPush(request: BatchPushRequest): BatchPushResponse
}
```

`PushPhase.push()` now:
1. Makes a mutable copy: `val mPending = pending.toMutableList()`
2. Calls `api.batchPush(ready.request)`
3. Calls `api.onBeforeResponseLoop(mPending, response)`
4. Derives patches from `mPending`, which now reflects any mutations

**Benefits:**
- `SyncApiClient` has a named, documented seam that has meaning in production (logging, metrics, optimistic UI)
- `pending` in `PushPlan.Ready` is back to `List` — the plan is an immutable snapshot
- The test sets `api.onBeforeResponseLoop = { pending, _ -> pending.removeIf { ... } }` — no override needed
- `SupabaseSyncApiClient` implements it as a no-op: `override var onBeforeResponseLoop: ... = { _, _ -> }`

## Consequences

- `SyncApiClient` now exposes one property (`onBeforeResponseLoop`) that is `!= null` only in tests
- `pendingRef` removed from `SyncApiClient`, `SupabaseSyncApiClient`, `FakeSyncApiClient`
- `PushPlan.Ready.pending` is now `List` (was `MutableList`)
- The D1 test in `PushPhaseTest` is simplified: no anonymous `object : FakeSyncApiClient()` override

## Links

- `SyncApi.kt` — `SyncApiClient` interface
- `PushPhase.kt` — `PushPhase.push()` implementation
- `PushPlan.kt` — `PushPlan.Ready` definition
- `TestUtils.kt` — `FakeSyncApiClient`
- `SupabaseSyncApiClient.kt` — production implementation
- `PushPhaseTest.kt` — D1 test

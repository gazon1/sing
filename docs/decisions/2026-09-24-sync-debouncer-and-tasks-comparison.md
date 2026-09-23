---
description: Request coalescing in SyncRepository.syncOnce, and analysis of Tasks KMP sync architecture for potential adoption.
status: accepted
---

# ADR: Sync Coalescing + Tasks KMP Architecture Survey

## Context

Two questions arose during Tier 3.5 development:

1. **Should we add time-based debouncing to `SyncRepository.syncOnce()`?** `AutoSync.trigger()` can fire multiple calls in quick succession (network callback + app-resume + UI sync button within 200 ms), potentially spawning overlapping sync runs.
2. **What can we borrow from the Tasks KMP sync layer?** The linked directory `org/tasks/sync` appears at first glance to be a mature sync implementation.

## Decision

### 1. Adopt request coalescing (not time-based debounce)

**Pattern:** *request coalescing* — if `syncOnce()` is called while a sync is already running, the second call returns immediately with `SyncOutcome.Skipped` and sets a flag; when the running sync finishes, one follow-up sync is launched automatically.

**Implementation** in `SyncRepositoryImpl`:

```kotlin
private val pendingFollowUp = AtomicBoolean(false)

override suspend fun syncOnce(): SyncOutcome {
    if (engine.status.value.isRunning()) {
        pendingFollowUp.set(true)
        return SyncOutcome.Skipped("Another sync is running")
    }
    val outcome = engine.syncOnce()
    if (pendingFollowUp.compareAndSet(true, false)) {
        scope.launch { syncOnce() }
    }
    return outcome
}
```

**Why not time-based `Flow.debounce(500.ms)`?**

| | Coalescing | Flow.debounce |
|---|---|---|
| Complexity | ~15 lines, no new types | requires `SharedFlow`, time window tuning |
| Correctness | request coalescing = exact once | debounce = may drop triggers if window too short |
| UI impact | None (already guarded in `SyncViewModel`) | Risk of delaying a user-initiated sync |

Tasks KMP uses `debounce(500.ms)` in `SyncAdapters` because their UI trigger and background trigger both feed the same `Channel`. We already have a UI-side `isRunning()` guard in `SyncViewModel.syncNow()`, so coalescing is the right abstraction here.

**Why `AtomicBoolean` and not a `Mutex` or `Job` count?**

- `AtomicBoolean` is lock-free and doesn't require `suspend` to check or set.
- A `Mutex` would serialize reads but we only need a single pending flag.
- A job count would be more general but we only need one follow-up, not N.

### 2. Tasks KMP — reject 4 of 5 proposed patterns

The linked `org/tasks/sync` directory is **not** an Orgzly-style HLC/outbox architecture. It is a thin dispatcher over CalDAV/Microsoft/Etebase SDKs. Most "sync patterns" in that directory are protocol-specific and not applicable to our design.

| Pattern | Verdict | Reason |
|---|---|---|
| 1. Debounced dispatcher (`Channel<SyncSource> + debounce(1s)`) | **Already addressed above** | We adopted request coalescing; the time-based debounce in Tasks KMP is less applicable than it first appears |
| 2. `SyncSource.upgrade(other)` priority merge | **Defer** | Our `SyncTrigger` enum is lifecycle-shaped (Created/Updated/AppResumed). Tasks KMP's intent-shaped triggers (USER/PUSH/BACKGROUND) enable priority. We don't have preemptive triggers. |
| 3. Per-row `dirtyVersion` claim token in `SyncOutbox` | **Reject** | Our `ConflictResolver.shadowChecksum` (SHA-256 over sorted JSON keys) already detects local edits during push: server returns `shadow_mismatch`, we re-queue. Adding a claim token is duplicate machinery. |
| 4. Server-opaque `String` cursor instead of `Long` LSN | **Defer (server change)** | `getEventsSince(userId, sinceLsn: Long, limit: Int)` is our wire format. Tasks KMP stores `etag`/`deltaLink` as opaque strings. Switching requires coordinated Supabase Edge Function change. |
| 5. SSE-driven push trigger with capped exponential backoff | **Defer (server change)** | Tasks KMP `SseClient` uses `INITIAL_BACKOFF_MS = 1_000L`, `MAX_BACKOFF_MS = 60_000L`. We have no real-time sync. Requires Supabase Realtime channel + Edge Function emitter. |

### 3. SyncOutcome sealed interface

`SyncOutcome` was a plain `data class`. To represent `Skipped` (a sync that did not run because another was in progress), it is now a `sealed interface` with two variants:

- `SyncOutcome.Success(push: Result<PushSummary>, pull: Result<PullSummary>)` — sync ran to completion
- `SyncOutcome.Skipped(reason: String)` — coalesced, no sync occurred

`SyncEngine.syncOnce()` always returns `Success`. `SyncRepositoryImpl.syncOnce()` may return `Skipped` when the engine is busy.

## Consequences

- **Positive:** Multiple rapid triggers (network + lifecycle + UI) now produce at most 2 sync runs (one immediate, one follow-up). No concurrent overlapping syncs.
- **Positive:** `SyncOutcome.Skipped` is explicit — callers (ViewModel, AutoSync) can distinguish "sync didn't run" from "sync failed".
- **Neutral:** `SyncRepositoryImpl` now requires a `CoroutineScope` injection for the follow-up launch. DI binding in `CoreDiModule` passes `AutoCloseableCoroutineScope(createBackgroundScope().coroutineContext)`.
- **Positive:** `FakeSyncRepository.syncOnce()` mirrors the same `isRunning()` guard — tests accurately reflect real behavior.

## References

Tasks KMP sync sources (all raw GitHub URLs):

- [SyncSource.kt](https://raw.githubusercontent.com/tasks/tasks/main/kmp/src/commonMain/kotlin/org/tasks/sync/SyncSource.kt) — `enum SyncSource` with `upgrade()` priority merge
- [SyncAdapters.kt](https://raw.githubusercontent.com/tasks/tasks/main/kmp/src/commonMain/kotlin/org/tasks/sync/SyncAdapters.kt) — `Channel<SyncSource>` + `debounce(1000L)` dispatcher
- [MicrosoftService.kt](https://raw.githubusercontent.com/tasks/tasks/main/kmp/src/commonMain/kotlin/org/tasks/sync/microsoft/MicrosoftService.kt) — deltaLink cursor, opaque `nextDelta: String?`
- [CaldavSynchronizer.kt](https://raw.githubusercontent.com/tasks/tasks/main/kmp/src/commonMain/kotlin/org/tasks/caldav/CaldavSynchronizer.kt) — `DirtyDao.withDirtyVersion()` claim token
- [SseClient.kt](https://raw.githubusercontent.com/tasks/tasks/main/kmp/src/commonMain/kotlin/org/tasks/sse/SseClient.kt) — exponential backoff capped at 60s

Implementation files in this branch:

- `core/sync/SyncEngine.kt` — `SyncOutcome.Success` / `SyncOutcome.Skipped` sealed interface
- `core/sync/SyncRepositoryImpl.kt` — `pendingFollowUp: AtomicBoolean` coalescing
- `core/di/CoreDiModule.kt` — scope injection into `SyncRepositoryImpl`
- `jvmTest/.../core/sync/SyncRepositoryCoalescingTest.kt` — 4 test cases

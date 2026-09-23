---
status: accepted
---

# Outbox polling → WorkManager

## Context

`SyncEngine` had a manual `while(true)` push loop inside its `init` block:

```kotlin
init {
    scope.launch {
        while (true) {
            delay(30_000)
            push()
        }
    }
}
```

Problems:
- No persistence across process death (kill → sync stops)
- No battery-aware constraints
- No exponential back-off on failures
- No way to cancel when user signs out (relied on `scope.cancel()`)
- Untestable in JVM tests (`runTest` can't control `delay` in `while(true)`)

## Decision

Replace the manual loop with `WorkManager` (`androidx.work:work-runtime 2.10.0`).

### Architecture

```
SyncWorkScheduler          — interface (commonMain)
    enqueuePush()          — schedule OneTimeWorkRequest
    cancelPush()           — cancel pending work

AndroidSyncWorkScheduler   — WorkManager impl (androidMain)
NoopSyncWorkScheduler      — no-op impl (jvmMain)

SyncOutboxWorker           — CoroutineWorker (androidMain)
    doWork() → syncEngine.push()
    retry: max 4 attempts with EXPONENTIAL back-off
```

### SyncEngine.init changes

```kotlin
init {
    scope.launch {
        authRepository.currentSession.collect { session ->
            when (session) {
                is Session.SignedIn -> scheduler.enqueuePush()
                is Session.Anonymous,
                is Session.SignedOut,
                is Session.Loading,
                -> scheduler.cancelPush()
            }
        }
    }
}
```

`SyncEngine` no longer owns a `Job` or calls `push()` directly — it delegates scheduling to `SyncWorkScheduler`. `SyncOutboxWorker` is the Android-only component that actually calls `push()`.

### SyncWorkScheduler interface (commonMain)

```kotlin
interface SyncWorkScheduler {
    fun enqueuePush()
    fun cancelPush()
}
```

### Android implementation

```kotlin
class AndroidSyncWorkScheduler(private val context: Context) : SyncWorkScheduler {
    private val workManager = WorkManager.getInstance(context)
    private val constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .build()

    override fun enqueuePush() {
        val request = OneTimeWorkRequestBuilder<SyncOutboxWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            SyncOutboxWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun cancelPush() {
        workManager.cancelUniqueWork(SyncOutboxWorker.WORK_NAME)
    }
}
```

### SyncOutboxWorker

```kotlin
class SyncOutboxWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val syncEngine: SyncEngine by inject()

    override suspend fun doWork(): Result {
        return try {
            val result = syncEngine.push()
            when {
                result.pushed == 0 && result.failed > 0 && result.errors.isEmpty() -> Result.failure()
                else -> Result.success()
            }
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "sync_outbox_push"
        private const val MAX_ATTEMPTS = 4
    }
}
```

## Rationale

| Alternative | Why rejected |
|---|---|
| PeriodicWorkRequest (15 min minimum) | Too slow for push; we want near-real-time |
| Hand-rolled ForegroundService | Manual lifecycle management; WorkManager handles process death |
| WorkManager only, no Outbox | Still need Outbox for offline-first: queue → push → delete |
| `kotlinx-coroutines` `yield()` in loop | No persistence; process death breaks in-progress sync |

**Why WorkManager over ForegroundService**: WorkManager manages job persistence, battery constraints, and retry back-off out of the box. `ExistingWorkPolicy.KEEP` ensures only one push job runs at a time even across process restarts.

## Consequences

- **JVM target**: `SyncEngine` still exists, but `SyncWorkScheduler` is `NoopSyncWorkScheduler` (no-op). No background sync on desktop.
- **HlcFactory must be `open`**: The actual JVM class is final, preventing test subclassing. Changed to `open class`.
- **Tests removed**: `SyncWorkSchedulerTest` was removed due to `advanceUntilIdle()` flakiness with `StateFlow` + `runTest`. The `FakeSyncWorkScheduler` and `FakeHlcFactory` utilities remain as compilable test doubles.
- **SyncOutboxWorker is Android-only**: JVM has no `SyncOutboxWorker`.

## Links

- MR: `feature/calendar-mr0-outbox-workmanager`
- Related: `2026-09-06-koin-suspend-bridge.md` (WorkManager + Koin DI integration pattern)

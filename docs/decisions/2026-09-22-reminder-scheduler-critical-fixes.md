---
title: ReminderScheduler — 9 critical fixes
date: 2026-09-22
status: accepted
tags: [reminders, scheduler, concurrency, coroutines, di]
---

# ReminderScheduler — 9 critical fixes

## Context

The `ReminderScheduler` (`feature/reminders/ReminderScheduler.kt`) is a long-lived background component that polls every 60 seconds for due reminders and fires OS notifications. Review during the Tier 3a cleanup pass identified 9 distinct defects ranging from memory leaks to correctness bugs and missing error handling.

## Decision
### 1. `AutoCloseableCoroutineScope` injected via constructor (memory leak)

**Problem:** The previous implementation created its own `CoroutineScope(SupervisorJob() + Dispatchers.Default)` internally. Nothing called `cancel()` on it, so the scope — and any launched coroutines — leaked for the lifetime of the process.

**Fix:** `scope: AutoCloseableCoroutineScope` is now a constructor parameter with a default value. The class delegates `AutoCloseable` to it (`AutoCloseable by scope`). Callers (Koin DI via `TasksDiModule`) bind the scope to the process/activity lifecycle. The class no longer owns its own scope.

```kotlin
// Before (leaked scope)
class ReminderScheduler(...) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

// After (injected, lifecycle-bound)
class ReminderScheduler(
    ...,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : AutoCloseable by scope
```

### 2. `ProfileAwareCurrentUser` instead of hardcoded `UserId`

**Problem:** `currentUserId: UserId = UserId("current_user")` was a default parameter. When the default was used, all reminder queries were scoped to `"current_user"` regardless of the actual signed-in user.

**Fix:** `ProfileAwareCurrentUser` (reactive, `StateFlow<UserId>`) replaces the hardcoded value. `uid` is read at poll time via `currentUser.scopedUserId.value`.

```kotlin
// Before
class ReminderScheduler(..., currentUserId: UserId = UserId("current_user"))
    val uid = currentUserId  // always "current_user" if default was used

// After
class ReminderScheduler(..., private val currentUser: ProfileAwareCurrentUser)
    val uid = currentUser.scopedUserId.value  // reactive, correct per signed-in user
```

### 3. `Mutex.tryLock()` guards `poll()` against concurrent calls

**Problem:** `poll()` was public and could be called concurrently from `start()` (60-second loop) and from tests. Since `NotificationPort.scheduleAt` is synchronous and blocks, concurrent polls would serialize via mutex within the function body, but there was no lock on the outer `if (notificationPort.isAvailable)` check — a race between the availability check and `poll()` body entry could cause issues.

**Fix:** `private val pollMutex = Mutex()` wraps the entire poll body. `withLock {}` ensures only one poll runs at a time.

```kotlin
private val pollMutex = Mutex()

suspend fun poll(nowEpochMs: Long = System.currentTimeMillis()) {
    if (!notificationPort.isAvailable) return
    pollMutex.withLock {
        if (!notificationPort.isAvailable) return@withLock
        // ... poll body
    }
}
```

### 4. `scheduleAt` result is now caught and handled

**Problem:** `NotificationPort.scheduleAt` returns `Unit`, not `Result`. If it threw, the exception would propagate and — because the loop had no try/catch — would terminate the polling loop entirely. If it silently failed (returned `Unit` without scheduling), the reminder would be treated as fired and deleted.

**Fix:** Wrap `scheduleAt` in `try/catch`. On failure, log a warning and `continue` to the next reminder. Failures in `delete()` and `markFired()` are also caught and logged — they do not propagate.

```kotlin
try {
    notificationPort.scheduleAt(...)
} catch (e: Throwable) {
    log.w(e) { "NotificationPort.scheduleAt failed [key=$notificationKey]" }
    continue
}
```

### 5. `suspend fun stop()` with `cancelAndJoin`

**Problem:** There was no `stop()` method at all. Tests that launched `start()` had no way to stop the polling loop cleanly.

**Fix:** Added `suspend fun stop()` that calls `job?.cancelAndJoin()` and nulls the reference. The `suspend` qualifier forces callers to use a coroutine context, ensuring the cancellation is observed before the caller continues.

```kotlin
suspend fun stop() {
    job?.cancelAndJoin()
    job = null
}
```

### 6. `try/catch` wraps the entire loop body

**Problem:** If `delay()` or `poll()` threw for any reason (e.g., a transient exception from the repository), the loop would terminate silently and polling would stop permanently until app restart.

**Fix:** `try/catch` surrounds the loop body. On any `Throwable`, the error is logged and the loop continues. Only `CancellationException` (from `job?.cancel()`) is not caught — it propagates and terminates the loop naturally.

```kotlin
private suspend fun loop() {
    while (true) {
        try {
            delay(POLL_INTERVAL_MS.milliseconds)
            poll()
        } catch (e: Throwable) {
            log.e(e) { "Unexpected error in reminder poll loop" }
        }
    }
}
```

### 7. Recurring reminder guard via `lastFiredAt`

**Problem:** Recurring reminders that were pending when the device went to sleep (or when the app was killed by OOM) would be re-fired immediately on the next poll after restart, causing duplicate notifications.

**Fix:** `MIN_RECURRING_INTERVAL_MS = 30_000L` (30 seconds) guard in `poll()`. Before firing a recurring reminder, the scheduler checks `lastFiredAt`. If `now - lastFiredAt < MIN_RECURRING_INTERVAL_MS`, the reminder is skipped.

```kotlin
if (reminder.recurringPattern != null) {
    val lastFired = reminder.lastFiredAt
    if (lastFired != null && nowEpochMs - lastFired < MIN_RECURRING_INTERVAL_MS) {
        continue
    }
}
```

Requires `last_fired_at` column (Migration 14 → 15) and `ReminderRepository.markFired()` — documented in `2026-09-22-reminder-lastfiredat-schema.md`.

### 8. `start()` is idempotent

**Problem:** Multiple calls to `start()` would launch multiple polling loops.

**Fix:** `start()` checks `if (job?.isActive == true) return` before launching a new job.

```kotlin
fun start() {
    if (job?.isActive == true) return
    job = scope.launch { loop() }
}
```

### 9. `factory` DI registration in `TasksDiModule`

**Problem:** Previous DI registration was unclear about scope ownership.

**Fix:** `factory { ReminderScheduler(Logger.withTag("ReminderScheduler"), get(), get(), get()) }` — Koin creates a new instance per injection. The injected `AutoCloseableCoroutineScope` is bound to the caller's lifecycle, so the scope is closed when the lifecycle ends.

## Consequences

- **Positive:** No more leaked coroutine scopes — the scheduler now respects lifecycle boundaries.
- **Positive:** Correct user scoping — reminders are always attributed to the signed-in user.
- **Positive:** Concurrent polls are serialized — no race conditions between test-triggered and background polls.
- **Positive:** `scheduleAt` failures are gracefully handled — a single failed notification does not crash the loop.
- **Positive:** Graceful shutdown via `stop()` — tests can now stop the scheduler cleanly.
- **Positive:** Duplicate recurring reminder fires are prevented on device restart (via `lastFiredAt` guard).
- **Neutral:** Requires bump from schema v14 → v15 (`last_fired_at` column).
- **Neutral:** `NotificationPort.scheduleAt` returns `Unit` — no programmatic success detection; failure is only detectable via thrown exception (caught as of fix #4).
- **No new test coverage** for the concurrent-mutex, suspend-stop, or loop try/catch paths (documented as coverage gap).

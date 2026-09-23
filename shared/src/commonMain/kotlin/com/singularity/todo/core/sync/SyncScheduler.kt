package com.singularity.todo.core.sync

import kotlin.time.Duration

/**
 * Platform-specific sync scheduler.
 *
 * Android: uses WorkManager PeriodicWorkRequest.
 * JVM:     uses CoroutineScope + delay() loop.
 *
 * In Tier 1 this is a no-op stub ([NoOpSyncScheduler]) wired in DI.
 * Tier 2 replaces it with real platform implementations.
 */
interface SyncScheduler {
    /**
     * Schedules periodic sync with the given [interval].
     */
    fun schedule(interval: Duration)

    /**
     * Cancels any scheduled sync.
     */
    fun cancel()
}

/**
 * No-op scheduler used until Tier 2 provides platform-specific implementations.
 */
class NoOpSyncScheduler : SyncScheduler {
    override fun schedule(interval: Duration) { /* no-op */ }
    override fun cancel() { /* no-op */ }
}

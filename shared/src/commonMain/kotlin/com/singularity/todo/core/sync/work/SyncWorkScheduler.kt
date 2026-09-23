package com.singularity.todo.core.sync.work

/**
 * Port for scheduling sync jobs via the platform's background job scheduler.
 *
 * Android: delegates to [androidx.work.WorkManager].
 * JVM:     no-op stub — sync is disabled on desktop.
 */
interface SyncWorkScheduler {

    /**
     * Enqueues a one-shot push job.
     * Uses `ExistingWorkPolicy.KEEP` — subsequent calls while a job is running
     * are idempotent; the running job completes before the new one starts.
     */
    fun enqueuePush()

    /**
     * Cancels any pending push job.
     */
    fun cancelPush()
}

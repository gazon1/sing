package com.singularity.todo.core.sync.work

/**
 * JVM stub implementation of [SyncWorkScheduler].
 *
 * Desktop has no background job scheduler, so the reactive half of sync does not
 * exist here: a desktop app is in the foreground when the user changes something, and
 * [com.singularity.todo.core.sync.DelayLoopSyncPeriodicTrigger] covers the periodic
 * half.
 */
class NoopSyncWorkScheduler : SyncWorkScheduler {
    override fun enqueuePush() {
        // No background scheduler on the JVM.
    }

    override fun cancelPush() {
        // No-op on JVM
    }

    override fun enqueuePeriodic(intervalMillis: Long) {
        // Periodic sync on the JVM is driven by the delay loop, not by a work queue.
    }

    override fun cancelPeriodic() {
        // No-op on JVM
    }
}

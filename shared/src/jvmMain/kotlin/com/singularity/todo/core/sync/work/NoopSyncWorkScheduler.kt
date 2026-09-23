package com.singularity.todo.core.sync.work

/**
 * JVM stub implementation of [SyncWorkScheduler].
 * Sync is not supported on desktop — no-op for all operations.
 */
class NoopSyncWorkScheduler : SyncWorkScheduler {
    override fun enqueuePush() {
        // No-op on JVM — sync is a mobile-only feature
    }

    override fun cancelPush() {
        // No-op on JVM
    }
}

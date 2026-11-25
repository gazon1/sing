package com.singularity.todo.core.sync

import com.singularity.todo.core.error.AppError
import kotlinx.coroutines.flow.StateFlow

/**
 * Result of a connectivity test.
 */
sealed interface ConnectionTestResult {
    data object Success : ConnectionTestResult

    data class Failure(val error: AppError) : ConnectionTestResult
}

/**
 * Public facade for sync operations.
 *
 * Feature modules inject this — they must never depend on [SyncEngine] or [SyncRunner] directly.
 * Those classes are [internal] (Gradle module visibility).
 */
interface SyncRepository : AutoCloseable {
    /** Current sync engine status. */
    val status: StateFlow<SyncEngineStatus>

    /** Last push result, or null before first push. */
    val lastPush: StateFlow<Result<PushSummary>?>

    /** Last pull result, or null before first pull. */
    val lastPull: StateFlow<Result<PullSummary>?>

    /**
     * Tests connectivity to the sync server.
     */
    suspend fun testConnection(): ConnectionTestResult

    /**
     * Enqueues [entity] for sync (writes to local outbox).
     * Returns [Result.success] if the entity was written to the outbox.
     */
    suspend fun enqueue(entity: SyncableEntity): Result<Unit>

    /**
     * Enqueues many entities for sync in one batch.
     *
     * The same contract as [enqueue], carried out in one pass: one patch per entity,
     * each replacing that entity's previously queued patch, each carrying the
     * entity's own state. Nothing is summarised and nothing is dropped.
     *
     * It exists because the per-entity path writes the sync clock to disk once per
     * entity, and a profile wipe of ten thousand rows paid that ten thousand times.
     *
     * Returns [Result.success] if the entities were written to the outbox.
     */
    suspend fun enqueue(entities: List<SyncableEntity>): Result<Unit>

    /**
     * Runs one push + pull cycle.
     */
    suspend fun syncOnce(): SyncOutcome

    /**
     * Starts periodic sync at the given [interval].
     * Calls [SyncRunner.startScheduledSync].
     */
    fun startScheduledSync(interval: kotlin.time.Duration)

    /**
     * Stops periodic sync.
     * Calls [SyncRunner.stopScheduledSync].
     */
    fun stopScheduledSync()
}

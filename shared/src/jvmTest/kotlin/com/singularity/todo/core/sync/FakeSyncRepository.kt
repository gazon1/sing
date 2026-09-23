package com.singularity.todo.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake implementation of [SyncRepository] for tests.
 */
class FakeSyncRepository : SyncRepository {
    private val _status = MutableStateFlow<SyncEngineStatus>(SyncEngineStatus.Idle)
    override val status: StateFlow<SyncEngineStatus> = _status.asStateFlow()

    private val _lastPush = MutableStateFlow<Result<PushSummary>?>(null)
    override val lastPush: StateFlow<Result<PushSummary>?> = _lastPush.asStateFlow()

    private val _lastPull = MutableStateFlow<Result<PullSummary>?>(null)
    override val lastPull: StateFlow<Result<PullSummary>?> = _lastPull.asStateFlow()

    val enqueuedEntities = mutableListOf<SyncableEntity>()
    var syncOnceCalled = false

    override suspend fun enqueue(entity: SyncableEntity): Result<Unit> {
        enqueuedEntities.add(entity)
        return Result.success(Unit)
    }

    override suspend fun syncOnce(): SyncOutcome {
        syncOnceCalled = true
        return SyncOutcome(Result.success(PushSummary(0, 0, 0)), Result.success(PullSummary(0, 0, 0)))
    }

    override fun startScheduledSync(interval: kotlin.time.Duration) { /* no-op */ }
    override fun stopScheduledSync() { /* no-op */ }
    override fun close() { /* no-op */ }
}

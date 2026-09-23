package com.singularity.todo.core.sync

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope

/**
 * Fake implementation of [SyncRepository] for tests.
 */
open class FakeSyncRepository(
    private val testScope: TestScope? = null,
) : SyncRepository {
    private val _status = MutableStateFlow<SyncEngineStatus>(SyncEngineStatus.Idle)
    override val status: StateFlow<SyncEngineStatus> = _status.asStateFlow()

    private val _lastPush = MutableStateFlow<Result<PushSummary>?>(null)
    override val lastPush: StateFlow<Result<PushSummary>?> = _lastPush.asStateFlow()

    private val _lastPull = MutableStateFlow<Result<PullSummary>?>(null)
    override val lastPull: StateFlow<Result<PullSummary>?> = _lastPull.asStateFlow()

    val enqueuedEntities = mutableListOf<SyncableEntity>()
    var syncOnceCalled = false
    var syncOnceCallCount = 0

    /**
     * If true, [syncOnce] yields for 10 ms to simulate an async network call.
     */
    var syncOnceYields = false

    var syncOnceResult: Result<SyncOutcome> = Result.success(
        SyncOutcome(Result.success(PushSummary(0, 0, 0)), Result.success(PullSummary(0, 0, 0))),
    )
    val syncOnceOutcome get() = syncOnceResult.getOrThrow()

    /** Tracks the most recent syncOnce Job when [testScope] is provided. */
    var lastSyncOnceJob: Job? = null
        private set

    fun setStatus(status: SyncEngineStatus) {
        _status.value = status
    }

    override suspend fun enqueue(entity: SyncableEntity): Result<Unit> {
        enqueuedEntities.add(entity)
        return Result.success(Unit)
    }

    override suspend fun syncOnce(): SyncOutcome {
        syncOnceCalled = true
        syncOnceCallCount++
        if (syncOnceYields) {
            delay(10)
        }
        return syncOnceResult.getOrThrow()
    }

    override fun startScheduledSync(interval: kotlin.time.Duration) { /* no-op */ }
    override fun stopScheduledSync() { /* no-op */ }
    override fun close() { /* no-op */ }

    override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success
}

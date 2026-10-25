package com.singularity.todo.test.fakes

import com.singularity.todo.core.refresh.CompletedTaskArchiver
import com.singularity.todo.core.sync.ConnectionTestResult
import com.singularity.todo.core.sync.PullSummary
import com.singularity.todo.core.sync.PushSummary
import com.singularity.todo.core.sync.SyncEngineStatus
import com.singularity.todo.core.sync.SyncOutcome
import com.singularity.todo.core.sync.SyncRepository
import com.singularity.todo.core.sync.SyncableEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * The order the refresh's steps ran in.
 *
 * The order is the reason the archive step comes first: archiving writes to
 * the outgoing queue, and a synchronisation that pushed before the archive
 * ran would leave the server holding the un-archived copy. A test that only
 * counted the calls would not notice that — it needs the sequence.
 */
class RefreshCallLog {
    private val entries = mutableListOf<String>()

    /** Records one step. */
    fun record(entry: String) {
        entries += entry
    }

    /** The steps so far, in order. */
    fun snapshot(): List<String> = entries.toList()

    /** How many times one step ran. */
    fun count(entry: String): Int = entries.count { it == entry }

    /** Forgets everything recorded so far. */
    fun clear() {
        entries.clear()
    }

    companion object {
        /** The name the archive step is recorded under. */
        const val ARCHIVE_STEP = "archive"

        /** The name the synchronisation step is recorded under. */
        const val SYNC_STEP = "sync"
    }
}

/**
 * A [SyncRepository] that records what it was asked to do and answers with a
 * scripted outcome.
 *
 * No transport, no network, no mock: the refresh run asks the repository for
 * one synchronisation, and the fake says what happened.
 *
 * ## Why this one and not the other
 *
 * `com.singularity.todo.core.sync.FakeSyncRepository` lives in the shared
 * module's *test* source set, and the desktop module cannot see it — a test
 * source set is not on another module's compile classpath. The refresh
 * feature's tests run on the desktop too, so the fake has to be in the main
 * artifact.
 *
 * @param outcome what [syncOnce] returns while nothing else is scripted.
 * @param log the log the synchronisation step is recorded in, when the test
 *   needs to see the order of the steps.
 */
class FakeSyncRepository(var outcome: SyncOutcome = COMPLETED, private val log: RefreshCallLog? = null) :
    SyncRepository {

    /** How many times a synchronisation was asked for. */
    var syncOnceCalls: Int = 0
        private set

    /** The entities that were queued for synchronisation. */
    val enqueuedEntities: MutableList<SyncableEntity> = mutableListOf()

    /** Set it to make [syncOnce] throw instead of answering. */
    var throwsOnCall: Throwable? = null

    /** Set it to make [testConnection] fail. */
    var connectionResult: ConnectionTestResult = ConnectionTestResult.Success

    /** The intervals [startScheduledSync] was called with. */
    val scheduledIntervals: MutableList<Duration> = mutableListOf()

    /** How many times [stopScheduledSync] was called. */
    var stopScheduledSyncCalls: Int = 0
        private set

    private val _status = MutableStateFlow<SyncEngineStatus>(SyncEngineStatus.Idle)
    override val status: StateFlow<SyncEngineStatus> = _status

    private val _lastPush = MutableStateFlow<Result<PushSummary>?>(null)
    override val lastPush: StateFlow<Result<PushSummary>?> = _lastPush

    private val _lastPull = MutableStateFlow<Result<PullSummary>?>(null)
    override val lastPull: StateFlow<Result<PullSummary>?> = _lastPull

    override suspend fun testConnection(): ConnectionTestResult = connectionResult

    override suspend fun enqueue(entity: SyncableEntity): Result<Unit> {
        enqueuedEntities += entity
        return Result.success(Unit)
    }

    override suspend fun syncOnce(): SyncOutcome {
        syncOnceCalls++
        log?.record(RefreshCallLog.SYNC_STEP)
        throwsOnCall?.let { throw it }
        return outcome
    }

    override fun startScheduledSync(interval: Duration) {
        scheduledIntervals += interval
    }

    override fun stopScheduledSync() {
        stopScheduledSyncCalls++
    }

    override fun close() = Unit

    companion object {
        /** A cycle that ran and did not lose a phase. */
        val COMPLETED: SyncOutcome = SyncOutcome.Completed(
            push = Result.success(PushSummary(processed = 0, succeeded = 0, failed = 0)),
            pull = Result.success(PullSummary(received = 0, applied = 0)),
        )
    }
}

/**
 * A [CompletedTaskArchiver] that records the calls and answers with a
 * scripted count.
 *
 * @param outcome what [archiveCompletedTasks] returns while nothing else is
 *   scripted. A failure in it is the "the archive step failed" case.
 * @param log the log the archive step is recorded in, when the test needs to
 *   see the order of the steps.
 */
class FakeCompletedTaskArchiver(
    var outcome: Result<Int> = Result.success(0),
    private val log: RefreshCallLog? = null,
) : CompletedTaskArchiver {

    /** How many times the archive step ran. */
    var archiveCalls: Int = 0
        private set

    /** Set it to make [archiveCompletedTasks] throw instead of answering. */
    var throwsOnCall: Throwable? = null

    /**
     * When set, the archive step waits for it before answering.
     *
     * A gate, not a delay: the test decides the exact moment the run is
     * allowed to continue, and no virtual time has to pass for that to happen.
     */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun archiveCompletedTasks(): Result<Int> {
        archiveCalls++
        log?.record(RefreshCallLog.ARCHIVE_STEP)
        gate?.await()
        throwsOnCall?.let { throw it }
        return outcome
    }
}

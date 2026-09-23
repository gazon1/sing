package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * Production implementation of [SyncRepository].
 *
 * Wires together [SyncEngine], [SyncRunner], and [SyncPrefs].
 * The [SyncRunner] is started/stopped via [startScheduledSync]/[stopScheduledSync].
 *
 * This class is [internal] because it exposes [SyncEngine] and [SyncRunner] (both internal).
 */
internal class SyncRepositoryImpl(
    private val engine: SyncEngine,
    private val runner: SyncRunner,
    private val prefs: SyncPrefs,
    private val log: Logger = Logger.withTag("SyncRepository"),
) : SyncRepository {

    override val status: StateFlow<SyncEngineStatus> = runner.status
    override val lastPush: StateFlow<Result<PushSummary>?> = runner.lastPush
    override val lastPull: StateFlow<Result<PullSummary>?> = runner.lastPull

    override suspend fun enqueue(entity: SyncableEntity): Result<Unit> {
        return engine.enqueue(entity)
    }

    override suspend fun syncOnce(): SyncOutcome {
        return engine.syncOnce()
    }

    override fun startScheduledSync(interval: Duration) {
        runner.startScheduledSync(interval)
    }

    override fun stopScheduledSync() {
        runner.stopScheduledSync()
    }

    override fun close() {
        runner.stopScheduledSync()
    }
}

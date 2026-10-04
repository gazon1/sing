package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.toMessage
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * Production implementation of [SyncRepository].
 *
 * Wires together [SyncEngine], [SyncRunner] and [SyncCoordinator].
 * The [SyncRunner] is started/stopped via [startScheduledSync]/[stopScheduledSync].
 *
 * This class is [internal] because it exposes [SyncEngine] and [SyncRunner] (both internal).
 */
internal class SyncRepositoryImpl(
    private val engine: SyncEngine,
    private val runner: SyncRunner,
    private val coordinator: SyncCoordinator,
    private val api: SyncApiClient,
    private val authRepository: AuthRepository,
    private val log: Logger = Logger.withTag("SyncRepository"),
) : SyncRepository {

    override val status: StateFlow<SyncEngineStatus> = runner.status
    override val lastPush: StateFlow<Result<PushSummary>?> = runner.lastPush
    override val lastPull: StateFlow<Result<PullSummary>?> = runner.lastPull

    override suspend fun testConnection(): ConnectionTestResult {
        authRepository.currentSession.value as? Session.SignedIn
            ?: return ConnectionTestResult.Failure(AppError.Validation("Not signed in"))
        // No user id: the transport authenticates as whoever the session is, and a
        // client-supplied id here would be an argument the client could set wrongly.
        return api.testConnection().fold(
            onSuccess = { ConnectionTestResult.Success },
            onFailure = { ConnectionTestResult.Failure(it as? AppError ?: AppError.Unknown(it.toMessage())) },
        )
    }

    override suspend fun enqueue(entity: SyncableEntity): Result<Unit> = engine.enqueue(entity)

    /**
     * Requests a cycle and waits for its outcome.
     *
     * Coalescing and mutual exclusion both live in [SyncCoordinator]. This method
     * holds no state of its own, which is the point: a guard built here would be
     * readable by anyone, and the previous one was — see [SyncCoordinator] for why
     * reading the engine's status could not work.
     */
    override suspend fun syncOnce(): SyncOutcome = coordinator.request()

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

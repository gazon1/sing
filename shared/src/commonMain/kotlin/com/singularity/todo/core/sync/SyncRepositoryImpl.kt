package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.toMessage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
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
    private val api: SyncApiClient,
    private val authRepository: AuthRepository,
    private val scope: AutoCloseableCoroutineScope,
    private val log: Logger = Logger.withTag("SyncRepository"),
) : SyncRepository {

    override val status: StateFlow<SyncEngineStatus> = runner.status
    override val lastPush: StateFlow<Result<PushSummary>?> = runner.lastPush
    override val lastPull: StateFlow<Result<PullSummary>?> = runner.lastPull

    /**
     * Request coalescing: if a sync is already running when syncOnce() is called,
     * mark that a follow-up is desired. When the running sync finishes, one follow-up
     * sync fires to drain any requests that arrived during the run.
     *
     * Pattern borrowed from Tasks KMP SyncAdapters (no time-based debounce — we already
     * have UI-side isRunning() guard in SyncViewModel).
     */
    private val pendingFollowUp = AtomicBoolean(false)

    override suspend fun testConnection(): ConnectionTestResult {
        val session = authRepository.currentSession.value as? Session.SignedIn
            ?: return ConnectionTestResult.Failure(
                AppError.Validation("Not signed in", code = "sync.connection.not_signed_in"),
            )
        return api.testConnection(session.userId.value).fold(
            onSuccess = { ConnectionTestResult.Success },
            onFailure = {
                ConnectionTestResult.Failure(
                    it as? AppError ?: AppError.Unknown(it.toMessage(), code = "sync.connection.failed"),
                )
            },
        )
    }

    override suspend fun enqueue(entity: SyncableEntity): Result<Unit> = engine.enqueue(entity)

    override suspend fun syncOnce(): SyncOutcome {
        // If a sync is already running, coalesce: mark that we want a follow-up
        // when the current one finishes, then return immediately.
        if (engine.status.value.isRunning()) {
            pendingFollowUp.set(true)
            log.d { "syncOnce coalesced (another sync is running)" }
            return SyncOutcome.Skipped("Another sync is running")
        }

        val outcome = engine.syncOnce()

        // Drain: if any trigger fired during the sync, run one follow-up.
        if (pendingFollowUp.compareAndSet(true, false)) {
            log.d { "Coalescing follow-up sync after [$outcome]" }
            scope.launch { syncOnce() }
        }

        return outcome
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

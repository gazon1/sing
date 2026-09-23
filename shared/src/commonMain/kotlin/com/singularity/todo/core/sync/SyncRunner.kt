package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Orchestrates the polling loop for sync.
 *
 * Implements Orgzly's "global sync button" pattern: a single scheduled job
 * that drives [SyncEngine.syncOnce] periodically when signed in.
 *
 * This class is [internal] — constructed in the sync Koin module and hidden
 * from feature modules behind [SyncRepository].
 */
internal class SyncRunner(
    private val engine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val authRepository: AuthRepository,
    private val prefs: SyncPrefs,
    scope: AutoCloseableCoroutineScope,
    private val log: Logger = Logger.withTag("SyncRunner"),
) : AutoCloseable by scope {

    // Expose scope for coroutine builders that need an explicit CoroutineScope reference.
    private val scopeRef: CoroutineScope = scope

    val status = engine.status
    val lastPush = engine.lastPush
    val lastPull = engine.lastPull

    private var scheduledJob: Job? = null

    init {
        // React to session changes: auto-start/stop sync when auth state changes.
        scope.launch {
            authRepository.currentSession.collect { session ->
                when (session) {
                    is Session.SignedIn -> {
                        if (scheduledJob?.isActive != true && prefs.autoSyncEnabled) {
                            startScheduledSync(prefs.scheduledInterval)
                        }
                    }
                    is Session.Anonymous, is Session.SignedOut, is Session.Loading -> {
                        stopScheduledSync()
                    }
                }
            }
        }
    }

    /**
     * Starts periodic [syncOnce] at the given [interval].
     */
    fun startScheduledSync(interval: Duration) {
        scheduledJob?.cancel()
        scheduledJob = scopeRef.launch {
            while (scopeRef.isActive) {
                syncOnce()
                delay(interval)
            }
        }
        log.d { "Scheduled sync started (interval=$interval)" }
    }

    /**
     * Stops the periodic sync job.
     */
    fun stopScheduledSync() {
        scheduledJob?.cancel()
        scheduledJob = null
        log.d { "Scheduled sync stopped" }
    }

    /**
     * Runs one push + pull cycle if the user is signed in.
     */
    suspend fun syncOnce() {
        if (authRepository.currentSession.value !is Session.SignedIn) {
            log.d { "syncOnce skipped: not signed in" }
            return
        }
        engine.syncOnce()
    }
}

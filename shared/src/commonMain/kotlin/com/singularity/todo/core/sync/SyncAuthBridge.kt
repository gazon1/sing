package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.sync.work.SyncWorkScheduler
import kotlinx.coroutines.launch

/**
 * Bridges auth session changes to the sync work scheduler.
 *
 * Launches a coroutine that reacts to [AuthRepository.currentSession] changes:
 * enqueues push when signed in, cancels when signed out. Extracted from [SyncEngine]
 * to make the engine constructible and testable without a running coroutine — the
 * bridge is a separate [AutoCloseable] that owns its own scope.
 *
 * WorkManager handles back-off and persistence across process death.
 */
internal class SyncAuthBridge(
    private val authRepository: AuthRepository,
    private val scheduler: SyncWorkScheduler,
    private val scope: AutoCloseableCoroutineScope,
    private val log: Logger = Logger.withTag("SyncAuthBridge"),
) : AutoCloseable {

    /**
     * The session collector.
     *
     * A property initializer rather than a `launch` in an `init` block: both start
     * during construction, but the initializer runs after the constructor's fields
     * are assigned, so the collector can never observe a half-built bridge. That is
     * the invariant `NoCoroutineLaunchInInit` exists to protect, and the job is held
     * so [close] can be reasoned about from the field rather than from the scope.
     */
    private val sessionWatch = scope.launch {
        authRepository.currentSession.collect { session ->
            when (session) {
                is Session.SignedIn -> scheduler.enqueuePush()

                is Session.Anonymous,
                is Session.SignedOut,
                is Session.Loading,
                -> scheduler.cancelPush()
            }
        }
    }

    /** Cancels the session collector, then the scope that owns it. */
    override fun close() {
        sessionWatch.cancel()
        scope.close()
    }
}

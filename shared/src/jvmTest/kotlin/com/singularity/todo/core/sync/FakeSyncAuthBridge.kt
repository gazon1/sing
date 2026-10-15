package com.singularity.todo.core.sync

import com.singularity.todo.core.auth.AuthRepository
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.sync.work.FakeSyncWorkScheduler
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Creates a [SyncAuthBridge] for use in tests that construct [SyncEngine] directly.
 *
 * Uses [FakeSyncWorkScheduler] (no-op scheduler) so the auth-session collector started
 * by [SyncAuthBridge] does nothing observable. Tests that need to verify scheduler
 * interactions pass their own [FakeSyncWorkScheduler] to the engine instead.
 *
 * The bridge is built with a [SupervisorJob]-backed scope so that closing the engine's
 * composite closeable cancels the collector without affecting the test scope.
 */
internal fun fakeSyncAuthBridge(authRepository: AuthRepository): SyncAuthBridge =
    SyncAuthBridge(
        authRepository = authRepository,
        scheduler = FakeSyncWorkScheduler(),
        scope = AutoCloseableCoroutineScope(SupervisorJob()),
        log = Logger.withTag("FakeSyncAuthBridge"),
    )

package com.singularity.todo.core.sync

import kotlin.time.Duration

/**
 * Platform-specific sync scheduler.
 *
 * Android: uses [AlarmManager][android.app.AlarmManager] — wakes the device and
 * triggers [SyncAlarmReceiver] which calls [SyncRepository.syncOnce].
 *
 * JVM: uses a daemon [kotlinx.coroutines.CoroutineScope] delay loop — fires
 * [onTick] at each [interval].
 *
 * The scheduler does NOT own the sync logic. It only provides the "when".
 * [SyncRunner] owns the lifecycle (start/stop) and the [SyncEngine] owns the
 * actual push+pull.
 */
interface SyncScheduler {
    /**
     * Schedules periodic sync with the given [interval].
     * On Android: arms [AlarmManager]; on JVM: launches a daemon coroutine loop.
     */
    fun schedule(interval: Duration)

    /**
     * Cancels any scheduled sync.
     */
    fun cancel()
}

/**
 * No-op scheduler used in tests and as the default until platform modules
 * override the binding with [AndroidSyncScheduler] (Android) or
 * [JvmSyncScheduler] (JVM).
 */
class NoOpSyncScheduler : SyncScheduler {
    override fun schedule(interval: Duration) { /* no-op */ }
    override fun cancel() { /* no-op */ }
}

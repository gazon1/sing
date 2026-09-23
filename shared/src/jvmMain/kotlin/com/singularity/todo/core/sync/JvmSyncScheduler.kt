package com.singularity.todo.core.sync

import co.touchlab.kermit.Logger
import kotlin.time.Duration

/**
 * JVM [SyncScheduler] — a no-op for scheduling (the [SyncRunner] delay loop
 * handles periodic sync on the JVM).
 *
 * On the JVM desktop there is no OS-level background scheduling equivalent to
 * Android's [AlarmManager][android.app.AlarmManager]. The [SyncRunner] handles
 * periodic sync via a [kotlinx.coroutines.delay] loop on [Dispatchers.Default].
 *
 * This class exists so the [SyncScheduler] binding can be overridden in the
 * JVM Koin module without special-casing the Android implementation.
 */
class JvmSyncScheduler : SyncScheduler {

    private val log = Logger.withTag("JvmSyncScheduler")

    override fun schedule(interval: Duration) {
        // SyncRunner owns the JVM delay loop; nothing to schedule here.
        log.d { "JvmSyncScheduler.schedule($interval) — no-op (SyncRunner loop handles it)" }
    }

    override fun cancel() {
        // SyncRunner stops its own job; nothing to cancel here.
        log.d { "JvmSyncScheduler.cancel() — no-op" }
    }
}

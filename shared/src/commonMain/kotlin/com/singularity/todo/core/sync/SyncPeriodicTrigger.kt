package com.singularity.todo.core.sync

import kotlin.time.Duration

/**
 * The platform-owned half of periodic sync: something that fires a request every
 * [interval], and can be stopped.
 *
 * The trigger does not run a sync cycle and does not decide *what* syncing means. It
 * only decides *when*, and it does that in a way the OS can or cannot survive:
 *
 * - Android: [com.singularity.todo.core.sync.work.SyncWorkScheduler] territory —
 *   WorkManager, which persists across process death, enforces its own backoff and
 *   respects battery and network constraints.
 * - JVM: a [kotlinx.coroutines.delay] loop, because a desktop app has no equivalent
 *   of AlarmManager that outlives its process.
 *
 * This seam exists because "which platform am I on" must not be answered by a type
 * test on an injected dependency. See [SyncRunner.startScheduledSync] for the bug
 * that produced it.
 */
internal interface SyncPeriodicTrigger {
    fun start(interval: Duration)
    fun stop()
}

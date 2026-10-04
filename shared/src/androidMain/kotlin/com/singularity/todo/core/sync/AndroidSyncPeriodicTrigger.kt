package com.singularity.todo.core.sync

import com.singularity.todo.core.sync.work.SyncWorkScheduler
import kotlin.time.Duration

/**
 * Android [SyncPeriodicTrigger]: WorkManager periodic work.
 *
 * WorkManager rather than `AlarmManager` because it already solves the three things a
 * repeating sync needs and that `setInexactRepeating` does not: it survives process
 * death, it applies its own backoff, and it can hold a battery constraint. The
 * `AlarmManager` implementation it replaces re-declared an `Intent`, a
 * `PendingIntent`, a request code, and a manifest entry, to arm a timer that the
 * platform then batched anyway.
 */
internal class AndroidSyncPeriodicTrigger(private val scheduler: SyncWorkScheduler) : SyncPeriodicTrigger {

    override fun start(interval: Duration) {
        scheduler.enqueuePeriodic(interval.inWholeMilliseconds)
    }

    override fun stop() {
        scheduler.cancelPeriodic()
    }
}

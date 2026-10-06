package com.singularity.todo.core.sync.work

import com.singularity.todo.core.work.BackgroundWorkScheduler
import com.singularity.todo.core.work.JobSchedule
import com.singularity.todo.core.work.SyncPushJob
import kotlin.time.Duration

/**
 * The Desktop [SyncWorkScheduler], and the first version of it that does anything.
 *
 * Replaces `NoopSyncWorkScheduler`, whose every method was a comment saying there was no
 * background scheduler here. `SyncEngine` calls `enqueuePush()` on every sign-in, so on
 * Desktop that was a patch queue that never drained — and no error anywhere, because a
 * no-op that is bound looks exactly like a no-op that works.
 *
 * ## Why it stays an adapter rather than being deleted
 *
 * `SyncEngine` is written against `SyncWorkScheduler` for good reason: it is the port, and
 * Android's implementation of it is WorkManager-backed and survives process death. Deleting
 * the port would mean either giving up that guarantee on Android or rewriting the engine's
 * call sites against a scheduler that cannot honour them. The adapter is the smaller,
 * reversible change.
 *
 * ## One caveat, stated rather than hidden
 *
 * [enqueuePush] and [enqueuePeriodic] address the same underlying job, so [cancelPush]
 * cancels the periodic schedule too. They are not independent resources on Desktop, and
 * pretending otherwise would hide a real coupling.
 *
 * ## What Desktop does not get
 *
 * Work survives only while the process lives. A cycle missed because the app was closed is
 * not replayed — unlike Android, where WorkManager holds the request. The periodic path is
 * a latency floor, not a guarantee.
 */
class JvmSyncWorkScheduler(private val background: BackgroundWorkScheduler) : SyncWorkScheduler {

    override fun enqueuePush() {
        background.runNow(SyncPushJob.ID)
    }

    override fun cancelPush() {
        background.cancel(SyncPushJob.ID)
    }

    override fun enqueuePeriodic(intervalMillis: Long) {
        background.schedule(SyncPushJob.ID, JobSchedule.Periodic(Duration.parse("${intervalMillis}ms")))
    }

    override fun cancelPeriodic() {
        background.cancel(SyncPushJob.ID)
    }
}

package com.singularity.todo.core.work

import com.singularity.todo.core.sync.SyncOutcome
import com.singularity.todo.core.sync.SyncRepository

/**
 * One full sync cycle — push **and** pull — as a [BackgroundJob].
 *
 * ## Why this is not `SyncOutboxWorker`
 *
 * `SyncOutboxWorker` is Android-only and stays where it is: it maps [SyncOutcome] onto
 * WorkManager `Result.retry()` with a `runAttemptCount` cap, which is a platform concern.
 * What was missing was a Desktop path, and the old `NoopSyncWorkScheduler` provided one that
 * did nothing — `SyncEngine` called `enqueuePush()` on every sign-in and the patches stayed
 * queued with no trace.
 *
 * So the *cycle* lives here in common code and both platforms reach it: Android through its
 * worker, Desktop through [JvmBackgroundWorkScheduler]. That is the "one background cycle,
 * two platforms" shape the worker KDoc already argues for when it routes through
 * [SyncRepository] instead of the engine.
 */
class SyncPushJob(private val syncRepository: SyncRepository) : BackgroundJob {

    override val id: String = ID

    override suspend fun run(): JobOutcome {
        val outcome = syncRepository.syncOnce()
        log(outcome)
        return when (outcome) {
            is SyncOutcome.Success -> {
                if (outcome.push.isFailure || outcome.pull.isFailure) {
                    // A partial failure is still a failure: one half of the cycle did not
                    // land, and reporting success would let the caller stand down.
                    JobOutcome.Failed(
                        outcome.push.exceptionOrNull()
                            ?: outcome.pull.exceptionOrNull()
                            ?: IllegalStateException("sync cycle partially failed"),
                    )
                } else {
                    JobOutcome.Success
                }
            }

            // Skipped means the coordinator coalesced this into another cycle, or it is
            // closed. The outcome is informational; the scheduler decides what to do next.
            is SyncOutcome.Skipped -> JobOutcome.Skipped(outcome.reason)

            // The cycle could not start — in practice a local read failed, so the patches
            // are still queued and nothing has been lost. The work is intact, not lost.
            is SyncOutcome.Failed -> JobOutcome.Failed(outcome.error)
        }
    }

    private fun log(outcome: SyncOutcome) {
        if (outcome is SyncOutcome.Success) {
            val push = outcome.push.getOrNull()
            logger.d {
                "sync cycle: processed=${push?.processed}, " +
                    "succeeded=${push?.succeeded}, failed=${push?.failed}"
            }
        } else {
            logger.d { "sync cycle: $outcome" }
        }
    }

    companion object {
        const val ID = "sync-push"
        private val logger = co.touchlab.kermit.Logger.withTag("SyncPushJob")
    }
}

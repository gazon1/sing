package com.singularity.todo.core.sync.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.original
import com.singularity.todo.core.observability.CrashReportingPort
import com.singularity.todo.core.sync.SyncOutcome
import com.singularity.todo.core.sync.SyncRepository
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * WorkManager worker that runs one full sync cycle — push **and** pull — in the
 * background.
 *
 * Constraints:
 * - `setRequiresBatteryNotLow(true)` — don't run when battery is critical
 * - No network constraint — the cycle handles its own auth/session checks internally
 *
 * The worker goes through [SyncRepository], not straight to the engine, so a
 * background cycle and a foreground one are the same cycle as far as coalescing is
 * concerned. A worker that called the engine directly would be a second unguarded
 * path to the cycle, which is how the desktop delay loop used to run alongside a
 * user-initiated sync.
 *
 * Retry policy: exponential back-off for transport failures, up to [MAX_ATTEMPTS].
 * A patch-level failure is the outbox's business and is retried with its own backoff
 * there, so it is not turned into a job-level retry here.
 *
 * Every escape route reports to [CrashReportingPort]. A worker that fails silently
 * looks exactly like a worker that never ran, and the only evidence of either is a
 * log line nobody is watching at three in the morning.
 */
class SyncOutboxWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {

    private val syncRepository: SyncRepository by inject()
    private val crashReporter: CrashReportingPort by inject()
    private val log = Logger.withTag("SyncOutboxWorker")

    override suspend fun doWork(): Result = try {
        when (val outcome = syncRepository.syncOnce()) {
            is SyncOutcome.Success -> {
                val push = outcome.push.getOrNull()
                log.d {
                    "Sync cycle: push processed=${push?.processed}, " +
                        "succeeded=${push?.succeeded}, failed=${push?.failed}"
                }
                if (outcome.push.isFailure || outcome.pull.isFailure) {
                    retryOrFail()
                } else {
                    Result.success()
                }
            }

            is SyncOutcome.Skipped -> {
                // Absorbed into another cycle, or the coordinator is closed.
                // Retrying is right for the former; for the latter a retry would
                // spin, and the runAttemptCount cap stops it.
                log.d { "Sync cycle skipped: ${outcome.reason}" }
                retryOrFail()
            }

            is SyncOutcome.Failed -> {
                // The cycle could not start — in practice a local read failed, so the
                // patches are still queued and nothing has been lost. That is the same
                // situation as the catch arm below: a transient condition with the
                // work intact, and the attempt cap is what stops a permanently broken
                // database from spinning.
                log.e(outcome.error.original()) { "Sync cycle could not start: ${outcome.error.message}" }
                retryOrFail()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.e(e) { "Sync work failed" }
        crashReporter.report(e, "sync.worker_failed")
        retryOrFail()
    }

    private fun retryOrFail(): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

    companion object {
        const val WORK_NAME = "sync_outbox_push"
        const val PERIODIC_WORK_NAME = "sync_periodic"
        private const val MAX_ATTEMPTS = 4
    }
}

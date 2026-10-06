package com.singularity.todo.core.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import co.touchlab.kermit.Logger
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * WorkManager implementation of [BackgroundWorkScheduler].
 *
 * ## How a wall-clock schedule survives WorkManager
 *
 * `PeriodicWorkRequest` cannot express "daily at 03:00" — a 24-hour period means "every 24
 * hours from enqueue", so a job enqueued at 09:00 fires at 09:00 and drifts further with
 * every Doze window.
 *
 * So [JobSchedule.Daily] and [JobSchedule.Weekly] are enqueued as **one-shot** requests
 * with an initial delay computed from the wall clock, and `BackgroundJobWorker` re-enqueues
 * its successor when it finishes. Only [JobSchedule.Periodic] — genuinely interval-shaped
 * work like polling — becomes a periodic request.
 *
 * ## KEEP semantics
 *
 * One-shot work uses `ExistingWorkPolicy.KEEP`, so scheduling a job that is already running
 * is a no-op rather than a second concurrent copy. Re-arming a wall-clock job after it fires
 * works because by then the previous request has finished.
 */
class AndroidBackgroundWorkScheduler(
    context: Context,
    private val catalog: BackgroundJobCatalog,
    private val clock: Clock,
    private val logger: Logger = Logger.withTag("AndroidBackgroundWork"),
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : BackgroundWorkScheduler {

    private val workManager = WorkManager.getInstance(context)

    override fun schedule(jobId: String, schedule: JobSchedule) {
        requireRegistered(jobId)
        when (schedule) {
            JobSchedule.OnDemand -> runNow(jobId)

            is JobSchedule.Periodic -> {
                val request = PeriodicWorkRequestBuilder<BackgroundJobWorker>(
                    maxOf(schedule.interval.inWholeMilliseconds, MIN_PERIODIC_MILLIS),
                )
                    .setConstraints(constraints())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF)
                    .setInputData(inputData(jobId, schedule))
                    .build()
                // KEEP: re-arming must not stack a second periodic request on the first.
                workManager.enqueueUniquePeriodicWork(
                    workName(jobId),
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }

            is JobSchedule.Daily, is JobSchedule.Weekly -> {
                val delay = JobScheduleMath.delayUntil(schedule, clock.now().toLocalDateTime(zone), zone)
                logger.d { "scheduling $jobId in $delay" }
                enqueueOneShot(jobId, schedule, delay)
            }
        }
    }

    override fun cancel(jobId: String) {
        workManager.cancelUniqueWork(workName(jobId))
    }

    override fun runNow(jobId: String) {
        requireRegistered(jobId)
        enqueueOneShot(jobId, JobSchedule.OnDemand, kotlin.time.Duration.ZERO)
    }

    private fun enqueueOneShot(
        jobId: String,
        schedule: JobSchedule,
        delay: kotlin.time.Duration,
    ) {
        val builder = OneTimeWorkRequestBuilder<BackgroundJobWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF)
            .setInputData(inputData(jobId, schedule))
        if (delay > kotlin.time.Duration.ZERO) {
            builder.setInitialDelay(delay)
        }
        workManager.enqueueUniqueWork(
            workName(jobId),
            ExistingWorkPolicy.KEEP,
            builder.build(),
        )
    }

    /**
     * An unknown id is a programming error, not a no-op.
     *
     * Silently doing nothing here is the failure this whole package exists to end: a bound
     * stub and an accepted call that resolves to nothing are indistinguishable from success
     * at every layer above.
     */
    private fun requireRegistered(jobId: String) {
        checkNotNull(catalog.find(jobId)) {
            "No background job with id '$jobId'. Register it in the catalogue before scheduling."
        }
    }

    /**
     * Encoded so the worker can re-arm itself without the scheduler holding state.
     *
     * [JobSchedule.OnDemand] is deliberately encoded too — a one-shot with no successor
     * re-arms into `OnDemand`, which runs once more and then stops. That is the right
     * shape for `runNow`.
     */
    private fun inputData(jobId: String, schedule: JobSchedule): Data = Data.Builder()
        .putString(BackgroundJobWorker.KEY_JOB_ID, jobId)
        .putString(BackgroundJobWorker.KEY_SCHEDULE, JobScheduleCodec.encode(schedule))
        .build()

    private fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    private fun workName(jobId: String) = "bg-$jobId"

    private companion object {
        /**
         * WorkManager's floor for periodic work. A shorter request is clamped, not rejected,
         * so log it: silently running a 60-second job every 15 minutes is worse than a line
         * explaining why it cannot be honoured.
         */
        const val MIN_PERIODIC_MILLIS = 15 * 60 * 1000L

        val BACKOFF = java.time.Duration.ofSeconds(30)
    }
}

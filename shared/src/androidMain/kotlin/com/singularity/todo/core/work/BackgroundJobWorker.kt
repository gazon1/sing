package com.singularity.todo.core.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import com.singularity.todo.core.observability.CrashReportingPort
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Runs one job from the [BackgroundJobCatalog].
 *
 * ## Why a single generic worker rather than one worker class per job
 *
 * WorkManager needs a `ListenableWorker` *class*, but common code cannot name one per job —
 * that would put `androidx.work` in commonMain. So the job id travels in the input data and
 * the catalogue resolves it here, which keeps the catalogue in common code and the
 * WorkManager types in androidMain.
 *
 * ## Re-arming
 *
 * A wall-clock schedule is realised as a one-shot that re-enqueues its own successor. So
 * after running, this worker asks the scheduler for the next occurrence. `JobSchedule` is
 * encoded in the input data so the worker can do that without holding scheduler state.
 *
 * Retry: exponential back-off, capped by [MAX_ATTEMPTS] in the request built by
 * [AndroidBackgroundWorkScheduler]. The cap matters — a permanently broken database must
 * stop burning battery, not retry forever.
 */
class BackgroundJobWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {

    private val catalog: BackgroundJobCatalog by inject()
    private val scheduler: BackgroundWorkScheduler by inject()
    private val crashReporter: CrashReportingPort by inject()
    private val log = Logger.withTag("BackgroundJobWorker")

    @Suppress("TooGenericExceptionCaught") // see KDoc: a thrown body must become a retry
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID)
            ?: return Result.failure().also { log.e { "worker started with no job id" } }
        val job = catalog.find(jobId) ?: return notFound(jobId)

        val outcome = try {
            job.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "$jobId failed" }
            crashReporter.report(e, "background_job.failed")
            JobOutcome.Failed(e)
        }

        // Re-arm before returning, so a retry does not stack a second copy of the schedule.
        val encoded = inputData.getString(KEY_SCHEDULE)
        if (encoded != null) {
            scheduler.schedule(jobId, JobScheduleCodec.decode(encoded))
        }

        return when (outcome) {
            is JobOutcome.Success -> {
                log.d { "$jobId: success" }
                Result.success()
            }

            is JobOutcome.Skipped -> {
                log.d { "$jobId: skipped (${outcome.reason})" }
                Result.success()
            }

            is JobOutcome.Failed -> {
                crashReporter.report(outcome.error, "background_job.failed")
                retryOrFail()
            }
        }
    }

    /**
     * A job id with no catalogue entry.
     *
     * Failures rather than success: a removed or renamed job that returned success would
     * re-arm forever on the same missing id, and the logs would show a healthy worker.
     */
    private fun notFound(jobId: String): Result {
        log.e { "no background job with id '$jobId'" }
        return Result.failure()
    }

    private fun retryOrFail(): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_SCHEDULE = "job_schedule"
        const val MAX_ATTEMPTS = 4
    }
}

/**
 * Encodes a [JobSchedule] into the string the worker carries in its input data.
 *
 * WorkManager input data is primitive types only, so this is a `Job` id plus a small tag and
 * three integers. Deliberately hand-rolled and total: an unrecognised string decodes to
 * [JobSchedule.OnDemand] rather than throwing, because a decode failure inside a worker is
 * a crash loop with no route out.
 */
object JobScheduleCodec {

    fun encode(schedule: JobSchedule): String = when (schedule) {
        JobSchedule.OnDemand -> "ondemand"

        is JobSchedule.Periodic -> "periodic:${schedule.interval.inWholeMilliseconds}"

        is JobSchedule.Daily -> "daily:${schedule.atHour}:${schedule.atMinute}"

        is JobSchedule.Weekly ->
            "weekly:${schedule.dayOfWeek.ordinal}:${schedule.atHour}:${schedule.atMinute}"
    }

    fun decode(encoded: String): JobSchedule {
        val parts = encoded.split(':')
        return when (parts.firstOrNull()) {
            "daily" -> parts.getOrNull(1)?.toIntOrNull()?.let { hour ->
                JobSchedule.Daily(hour, parts.getOrNull(2)?.toIntOrNull() ?: 0)
            } ?: JobSchedule.OnDemand

            "weekly" -> {
                val ordinal = parts.getOrNull(1)?.toIntOrNull()
                val hour = parts.getOrNull(2)?.toIntOrNull()
                val minute = parts.getOrNull(3)?.toIntOrNull()
                val day = kotlinx.datetime.DayOfWeek.entries.getOrNull(ordinal ?: -1)
                if (day != null && hour != null && minute != null) {
                    JobSchedule.Weekly(day, hour, minute)
                } else {
                    JobSchedule.OnDemand
                }
            }

            "periodic" -> parts.getOrNull(1)?.toLongOrNull()
                ?.let { JobSchedule.Periodic(kotlin.time.Duration.parse("${it}ms")) }
                ?: JobSchedule.OnDemand

            else -> JobSchedule.OnDemand
        }
    }
}

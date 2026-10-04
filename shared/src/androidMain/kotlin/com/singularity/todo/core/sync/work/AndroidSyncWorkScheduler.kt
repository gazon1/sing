package com.singularity.todo.core.sync.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Android implementation of [SyncWorkScheduler] backed by WorkManager.
 *
 * Uses `ExistingWorkPolicy.KEEP` for the one-shot job and
 * `ExistingPeriodicWorkPolicy.KEEP` for the periodic one, so repeated calls while a
 * job is in flight are idempotent rather than stacking duplicate syncs.
 */
class AndroidSyncWorkScheduler(private val context: Context) : SyncWorkScheduler {

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    private val constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .build()

    override fun enqueuePush() {
        val request = OneTimeWorkRequestBuilder<SyncOutboxWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .build()

        workManager.enqueueUniqueWork(
            SyncOutboxWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun cancelPush() {
        workManager.cancelUniqueWork(SyncOutboxWorker.WORK_NAME)
    }

    override fun enqueuePeriodic(intervalMillis: Long) {
        // WorkManager clamps a periodic request to at least 15 minutes and refuses
        // a flex shorter than 5. Ask for less than that and it throws at enqueue
        // time, taking the session-change collector down with it.
        val period = maxOf(intervalMillis, MIN_PERIODIC_MILLIS)
        val request = PeriodicWorkRequestBuilder<SyncOutboxWorker>(period, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            SyncOutboxWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    override fun cancelPeriodic() {
        workManager.cancelUniqueWork(SyncOutboxWorker.PERIODIC_WORK_NAME)
    }

    private companion object {
        /** WorkManager's documented minimum for a periodic request. */
        const val MIN_PERIODIC_MILLIS = 15 * 60 * 1000L
    }
}

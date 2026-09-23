package com.singularity.todo.feature.calendar_sync.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Android implementation of [CalendarSyncWorkScheduler] backed by WorkManager.
 *
 * Uses `ExistingWorkPolicy.KEEP` so concurrent `enqueueSync()` calls while a
 * job is running are idempotent.
 */
class AndroidCalendarSyncWorkScheduler(
    private val context: Context,
) : CalendarSyncWorkScheduler {

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    private val constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .build()

    override fun enqueueSync() {
        val request = OneTimeWorkRequestBuilder<CalendarSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .build()

        workManager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun cancelSync() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "calendar_sync"
    }
}

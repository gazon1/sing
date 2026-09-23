package com.singularity.todo.core.sync.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Android implementation of [SyncWorkScheduler] backed by WorkManager.
 *
 * Uses `ExistingWorkPolicy.KEEP` so concurrent `enqueuePush()` calls while a
 * job is running are idempotent — the existing job runs to completion.
 */
class AndroidSyncWorkScheduler(
    private val context: Context,
) : SyncWorkScheduler {

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    private val constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .build()

    override fun enqueuePush() {
        val request = OneTimeWorkRequestBuilder<SyncOutboxWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                backoffPolicy = BackoffPolicy.EXPONENTIAL,
                minimumInterval = 30,
                timeUnit = TimeUnit.SECONDS,
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
}

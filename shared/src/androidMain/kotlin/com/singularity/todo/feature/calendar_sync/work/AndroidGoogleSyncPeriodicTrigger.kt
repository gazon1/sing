package com.singularity.todo.feature.calendar_sync.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.singularity.todo.feature.calendar_sync.sync.GoogleSyncCoordinator
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Android [GoogleSyncPeriodicTrigger]: WorkManager periodic work running [GoogleSyncWorker].
 *
 * WorkManager rather than `AlarmManager` for the same reason its sibling uses it — it survives
 * process death, applies its own backoff, and holds constraints — and the one that matters
 * most for this feature: a sync the OS batches or defers while the app is dead is the only
 * kind of background sync worth having, because the user's phone is not going to be in the
 * app's hands when they next connect an account.
 *
 * ## Why the gate is a resolved coordinator and not a captured one
 *
 * [coordinatorProvider] is called per invocation rather than held, because the coordinator is
 * bound per profile: an instance captured when this trigger was built would keep whichever
 * profile was active at startup, and the next pass would run against the wrong account. The
 * gate is read by [GoogleSyncWorker] on each run for the same reason — a user who connects an
 * account ten minutes after launch gets background sync without a restart.
 *
 * @param context Android context, for [WorkManager.getInstance].
 * @param coordinatorProvider resolves a fresh coordinator per call — never captured.
 */
internal class AndroidGoogleSyncPeriodicTrigger(
    private val context: Context,
    private val coordinatorProvider: () -> GoogleSyncCoordinator,
) : GoogleSyncPeriodicTrigger {

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    private val constraints = Constraints.Builder()
        // A pass that cannot reach Google is a pass that fails, and WorkManager would spend
        // its retry budget discovering that on a device with no connection.
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    override fun start(interval: Duration) {
        // WorkManager clamps a periodic request to at least 15 minutes and throws at enqueue
        // time below that, taking the caller down with it — so the floor is applied here
        // rather than trusted to the constant.
        val period = maxOf(interval.inWholeMilliseconds, MIN_PERIODIC_MILLIS)
        val request = PeriodicWorkRequestBuilder<GoogleSyncWorker>(period, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    override fun stop() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    override suspend fun isConfigured(): Boolean = coordinatorProvider().isConfigured()

    companion object {
        /**
         * Distinct from the system calendar's `calendar_sync` work: the two passes are
         * unrelated, and sharing a unique-work name would make one cancel the other.
         */
        const val WORK_NAME = "google_calendar_sync"

        /** WorkManager's documented minimum for a periodic request. */
        private const val MIN_PERIODIC_MILLIS = 15 * 60 * 1000L
    }
}

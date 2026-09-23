package com.singularity.todo.core.sync.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import com.singularity.todo.core.sync.PushResult
import com.singularity.todo.core.sync.SyncEngine
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * WorkManager worker that runs [SyncEngine.push] in the background.
 *
 * Constraints:
 * - `setRequiresBatteryNotLow(true)` — don't run when battery is critical
 * - No network constraint — push handles its own auth/session checks internally
 *
 * Retry policy: exponential back-off, max 4 attempts (matches existing outbox
 * `markFailed` approach). On the 5th failure the work is marked `BLOCKED`
 * and won't retry automatically; a subsequent `enqueuePush()` call from
 * `SyncEngine.enqueue()` will restart it.
 */
class SyncOutboxWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val syncEngine: SyncEngine by inject()
    private val log = Logger.withTag("SyncOutboxWorker")

    override suspend fun doWork(): Result {
        return try {
            val result: PushResult = syncEngine.push()
            log.d { "Push completed: pushed=${result.pushed}, failed=${result.failed}" }
            when {
                // Terminal failures — don't retry
                result.pushed == 0 && result.failed > 0 && result.errors.isEmpty() -> Result.failure()
                // Partial success or retriable errors — retry with back-off
                else -> Result.success()
            }
        } catch (e: Exception) {
            log.e(e) { "Push work failed" }
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val WORK_NAME = "sync_outbox_push"
        private const val MAX_ATTEMPTS = 4
    }
}

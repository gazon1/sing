package com.singularity.todo.feature.calendar_sync.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import com.singularity.todo.feature.calendar_sync.sync.GoogleSyncCoordinator
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * WorkManager [CoroutineWorker] that runs one Google Calendar sync pass.
 *
 * Enqueued as periodic work by [AndroidGoogleSyncPeriodicTrigger] and resolved from Koin
 * rather than constructed, so a pass runs against the same graph the rest of the app uses.
 * All the logic lives in [GoogleSyncCoordinator]; this class decides only what WorkManager
 * should do with the result.
 *
 * ## The retry policy, and why a revoked grant gets none
 *
 * Two failure classes arrive here, and they are not the same thing:
 *
 * 1. **A pass that did not run, or did not finish.** The coordinator reports this as an
 *    outcome rather than throwing — deliberately, because a background pass has no UI to
 *    report to. This worker cannot tell a revoked grant from a dropped connection inside
 *    that outcome, so it must not guess. `Result.retry()` here would put a permanently dead
 *    credential into exponential backoff indefinitely, which is exactly the retry loop the
 *    sibling [CalendarSyncWorker] learned to avoid by mapping `PermissionRevokedException`
 *    onto `Result.success()`. Success it is: the next periodic run tries again on its own
 *    schedule, which is the correct cadence for a condition only the user can fix by
 *    reconnecting.
 * 2. **A pass that could not even be attempted.** The coordinator's own precondition reads —
 *    the calendar-settings DataStore and the credential store — sit outside its error
 *    handling, so a failure there reaches here as an exception. That is a transient
 *    condition with no user action attached, and it is what [Result.retry] is for.
 *
 * ## Why no status is written
 *
 * The coordinator does not write the shared system-calendar status, and neither does this:
 * writing it would put "Synced 14:02" under System Calendar for a pass that only touched
 * Google, which is the confidently-wrong answer the coordinator's own documentation refuses
 * to give. A Google status surface needs a screen behind it, and there is not one yet.
 *
 * @param context Android context.
 * @param params  Worker parameters.
 */
class GoogleSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {

    private val trigger: GoogleSyncPeriodicTrigger by inject()
    private val coordinator: GoogleSyncCoordinator by inject()
    private val log: Logger = Logger.withTag("GoogleSyncWorker")

    // See the class doc: only the precondition reads land here, and both are retryable.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result = try {
        runPass()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "Google sync pass could not be attempted; retrying" }
        Result.retry()
    }

    private suspend fun runPass(): Result {
        // The gate comes from the seam, not from the coordinator directly, so this worker
        // and the desktop delay loop ask the same question of the same code.
        if (!trigger.isConfigured()) {
            log.i { "Google sync pass skipped: no calendar selected, or no account connected" }
            return Result.success()
        }

        val outcome = coordinator.syncNow()
        return if (outcome.ran) {
            Result.success()
        } else {
            log.w { "Google sync pass did not run: ${outcome.skippedBecause}" }
            Result.success()
        }
    }
}

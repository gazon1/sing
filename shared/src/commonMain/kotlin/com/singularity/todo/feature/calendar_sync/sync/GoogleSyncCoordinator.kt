package com.singularity.todo.feature.calendar_sync.sync

import co.touchlab.kermit.Logger
import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.calendar_sync.auth.GoogleCredentialStore
import com.singularity.todo.feature.calendar_sync.domain.port.GoogleCalendarSettingsRepository
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The one entry point for "run a Google Calendar sync pass now".
 *
 * ## Why a coordinator rather than direct engine calls
 *
 * [GoogleSyncEngine] is a per-profile factory scoped to one calendar, and it takes no
 * decision about *whether* to run. Every caller would otherwise repeat the same four
 * preconditions, and any caller that forgot one would sync on top of an account the user
 * never connected. The preconditions are:
 *
 * 1. a calendar has been chosen,
 * 2. a credential exists for this profile,
 * 3. the profile is a real one, and
 * 4. the caller is not already inside a pass — guaranteed by the engine's own mutex, so
 *    it is not re-checked here.
 *
 * Point 3 is not paranoia. Every id-bearing store in this feature is keyed by user, and
 * `UserId.anonymous` is a value a signed-out session genuinely holds — so without this
 * check a signed-out app would sync whatever credential happens to sit under the
 * anonymous profile's key.
 *
 * ## Why it does not write the shared sync status
 *
 * [com.singularity.todo.feature.calendar_sync.domain.port.CalendarSyncRepository.setStatus]
 * backs the *system* calendar's status, and the settings screen renders it in the system
 * half. Recording a Google pass there would show "Synced 14:02" under System Calendar
 * while the Google half said nothing — a confidently wrong answer in the one place the
 * user has learned to trust. The Google half needs its own status surface, which is
 * deliberately not invented here: a field with no screen behind it is the same defect as a
 * control that does nothing. So the outcome is logged and returned, and the caller decides
 * where to show it.
 */
class GoogleSyncCoordinator(
    private val engineProvider: () -> GoogleSyncEngine,
    private val googleSettings: GoogleCalendarSettingsRepository,
    private val credentialStore: GoogleCredentialStore,
    private val currentUser: UserId,
    private val clock: Clock,
    private val logger: Logger = Logger.withTag("GoogleSyncCoordinator"),
) {

    /** What one coordinator-driven pass did, for a caller that wants to log or report it. */
    data class Outcome(
        val ran: Boolean,
        val result: GoogleSyncEngine.PassResult? = null,
        /** Why the pass did not run, when [ran] is false. */
        val skippedBecause: String? = null,
    ) {
        companion object {
            /** A pass that was correctly not run. A no-op is an outcome, not a failure. */
            fun skipped(reason: String) = Outcome(ran = false, skippedBecause = reason)
        }
    }

    /**
     * Runs one pass for [userId], if there is anything to run.
     *
     * Safe to call from a timer, a worker, and a button alike: the preconditions are
     * re-checked every time, and declining to run is a normal outcome rather than an error.
     */
    suspend fun syncNow(userId: UserId = currentUser): Outcome {
        if (userId == UserId.anonymous) return Outcome.skipped("not signed in")

        val calendarId = googleSettings.observeSelectedCalendarId().first()
        if (calendarId.isNullOrBlank()) return Outcome.skipped("no Google calendar selected")

        if (credentialStore.load(userId.value) == null) {
            return Outcome.skipped("no Google account connected")
        }

        return runCatchingResult { engineProvider().sync(calendarId) }.fold(
            onSuccess = { result ->
                logger.i {
                    "Google sync: ${result.seen} seen, ${result.pushed} pushed, " +
                        "${result.tasksCreated} tasks created, ${result.tasksUpdated} updated"
                }
                Outcome(ran = true, result = result)
            },
            onFailure = { error ->
                // Not rethrown. A background pass has nobody to throw to — the scheduler
                // driving it would retry with no explanation anywhere the user can see.
                logger.w(error) { "Google sync pass failed" }
                Outcome.skipped(error.message ?: "Google sync failed")
            },
        )
    }

    /**
     * Whether a periodic pass is worth scheduling at all.
     *
     * Read at arm time rather than once at startup: a user who connects an account ten
     * minutes after launch should get background sync without restarting the app, and one
     * who disconnects should stop being polled.
     */
    suspend fun isConfigured(userId: UserId = currentUser): Boolean {
        if (userId == UserId.anonymous) return false
        val calendarId = googleSettings.observeSelectedCalendarId().first()
        if (calendarId.isNullOrBlank()) return false
        return credentialStore.load(userId.value) != null
    }

    /** The instant a pass completed, for a caller that records it. */
    fun completedAt(): Instant = clock.now()
}

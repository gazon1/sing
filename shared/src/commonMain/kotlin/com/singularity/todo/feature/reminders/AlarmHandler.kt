package com.singularity.todo.feature.reminders

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.notifications.Notifier
import com.singularity.todo.feature.pomodoro.PomodoroPhase
import com.singularity.todo.feature.reminders.domain.port.ReminderRepository
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/**
 * What to do when an alarm arrives.
 *
 * ## Why this is in common code rather than in `AlarmReceiver`
 *
 * `AlarmReceiver` is a `BroadcastReceiver`. Testing one means Robolectric, and
 * `androidHostTest` currently declares no test at all — the JUnit 4 / Vintage stack it
 * would need is not wired, and the ADR about that records a live trap: the Vintage engine
 * does not map Jupiter's `@Tag` onto Platform tags, so such tests are silently skipped.
 *
 * A test that can be silently skipped is worse than no test, and paying to un-skip it is
 * a larger change than the thing under test. So the decisions are pulled out of the
 * receiver and this class takes plain parameters. What stays in `AlarmReceiver` is exactly
 * what is genuinely Android's: reading extras out of an `Intent`, and `goAsync()`.
 *
 * ## Why that is not over-abstraction
 *
 * Every branch in here is a decision with a failure mode, and none of them needs a
 * broadcast to exercise:
 *
 * - a pomodoro phase becomes a title, a body and a tag, and none of it was tested;
 * - the catch-up fires past-due reminders **before** re-arming, and that order is what
 *   stops a just-fired one-shot from being armed again in the same pass.
 *
 * @see AlarmReceiver — the Android adapter that calls into this.
 */
class AlarmHandler(
    private val reminderRepo: ReminderRepository,
    private val reminderScheduler: ReminderScheduler,
    private val delivery: ReminderDelivery,
    private val notifier: Notifier,
    private val clock: Clock,
) {

    /**
     * A scheduled reminder reached its fire time.
     *
     * [userId] is the profile the alarm was armed for, carried in the intent. It scopes
     * both the notification tag and the retirement, so a fire cannot touch another
     * profile's row.
     */
    suspend fun reminderFired(reminderId: ReminderId, userId: UserId) {
        delivery.fire(reminderId, userId)
    }

    /**
     * A pomodoro phase ended.
     *
     * The tag carries the phase and the current millisecond so successive phase ends
     * produce successive notifications rather than replacing one another — the same
     * reason `Notifier.post`'s tag exists, used the other way round.
     */
    suspend fun pomodoroPhaseEnded(phase: PomodoroPhase) {
        val title = when (phase) {
            PomodoroPhase.Work -> "Work session ended"
            PomodoroPhase.ShortBreak -> "Short break ended"
            PomodoroPhase.LongBreak -> "Long break ended"
        }
        val body = if (phase == PomodoroPhase.Work) "Time for a break ☕" else "Back to work!"

        notifier.post(
            tag = "pomodoro:$phase:${clock.now().toEpochMilliseconds()}",
            title = title,
            body = body,
            viewId = null,
        )
    }

    /**
     * Boot / data-changed catch-up: fire what was missed, then re-arm what is still ahead.
     *
     * ## The order is the invariant
     *
     * Fire first, re-arm second. Reversed, a one-shot that is past due would be deleted
     * by the fire and then re-armed in the same pass — and fire again on the next boot.
     * The one-shot's whole job is to not exist after it has fired.
     *
     * The past-due query is capped at [CATCH_UP_LIMIT]. A device that was off for a week
     * has no business presenting forty notifications on first unlock.
     */
    suspend fun catchUp() {
        val now = clock.now().toEpochMilliseconds()

        reminderRepo.watchRecentDueBefore(now, CATCH_UP_LIMIT).first()
            .forEach { delivery.fireKnown(it) }

        // Re-arm everything still in the future, across every profile.
        //
        // `observeAllProfiles`, not `observeAll`: this runs on BOOT_COMPLETED, and a
        // reminder belonging to a profile that is not active would otherwise never be armed
        // until its owner switched to it and relaunched. A reminder that just fired has
        // already been retired by the loop above, so it cannot reappear here.
        reminderRepo.observeAllProfiles().first()
            .filter { it.fireAt > now }
            .forEach { reminderScheduler.schedule(it) }
    }

    private companion object {
        /** Matches the cap the Android catch-up has always used; 20 notifications on unlock is already a lot. */
        const val CATCH_UP_LIMIT = 20
    }
}

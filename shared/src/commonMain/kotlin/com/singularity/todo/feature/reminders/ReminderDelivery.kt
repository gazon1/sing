package com.singularity.todo.feature.reminders

import co.touchlab.kermit.Logger
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.notifications.Notifier
import com.singularity.todo.feature.alarms.AlarmContract
import com.singularity.todo.feature.reminders.domain.port.ReminderRepository
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.core.error.runCatchingCancellable

/**
 * Delivers one reminder: read it, show it, retire it if it was a one-shot.
 *
 * ## Why this exists, and what it replaced
 *
 * These four steps — look up the row, read the fresh task title, compute the text with
 * [ReminderFireLogic], post, then delete if `shouldDelete` — were written **three times**:
 *
 * - `AlarmReceiver.handleReminderFire` (Android, single fire)
 * - `AlarmReceiver.rescheduleAll`'s catch-up loop (Android, boot catch-up)
 * - the Desktop `fire-reminder` launcher, via [ReminderDelivery] itself
 *
 * Three copies is past the point where "keep them the same" survives contact with a
 * codebase. Every one of them is correct today; the question is which one is correct in
 * six months, and the answer was going to be "whichever nobody touched". The steps that
 * could drift are exactly the ones that matter and are easiest to get subtly wrong:
 * whether the tag is scoped by the firing user or the row's owner, whether a delete
 * failure changes the outcome, and whether a missing task still produces a notification.
 *
 * ## What this does not own
 *
 * It does not own *when* a reminder fires, or the process lifecycle around it. Android
 * still drives it from a `BroadcastReceiver` with `goAsync()`; Desktop still drives it
 * from a one-shot process. Those are genuinely different and belong to their platforms.
 * What is not different is what happens to the row once the platform has decided to fire
 * it, and that is now written once.
 *
 * ## Ordering is load-bearing
 *
 * [fire] reads the row through [ReminderRepository.get], which is **scoped to the active
 * profile**. A caller that fires before the profile is resolved gets `null` and reports
 * [Outcome.NotFound] for a reminder that exists — a silent failure with a misleading
 * message. Both platforms resolve the profile first for this reason.
 */
class ReminderDelivery(
    private val reminderRepo: ReminderRepository,
    private val taskRepo: TaskRepository,
    private val notifier: Notifier,
    private val logger: Logger = Logger.withTag("ReminderDelivery"),
) {

    /** What happened when a reminder was fired. */
    enum class Outcome {
        /** The notification was handed to the notifier. */
        Posted,

        /**
         * No such reminder for the active profile.
         *
         * Either it was deleted already, or it belongs to a profile that is not active.
         * The second case is why callers must resolve the profile before firing.
         */
        NotFound,

        /**
         * The host cannot display a notification.
         *
         * A distinct outcome rather than a silent [Posted], because a reminder that was
         * armed and then could not be shown is the one case where the user is worse off
         * than if it had never been created — and it is diagnosable only from a log.
         */
        NoNotifier,
    }

    /**
     * Fire the reminder identified by [reminderId] on behalf of [userId].
     *
     * [userId] is the profile the fire is *charged to*, and it is what the delete is
     * scoped by — matching the alarm key, so a fire cannot retire another profile's row
     * even if the lookup were to return one.
     *
     * A failure to delete is logged and does not change the outcome: the notification was
     * shown, which is what the user was promised. Leaving the row behind means the
     * reminder fires again, which is recoverable; withholding the notification is not.
     */
    suspend fun fire(reminderId: ReminderId, userId: UserId): Outcome {
        if (!notifier.isSupported) {
            logger.w { "No notifier on this host; reminder ${reminderId.value} cannot be shown" }
            return Outcome.NoNotifier
        }

        val reminder = reminderRepo.get(reminderId) ?: run {
            logger.w { "Reminder not found for the active profile: ${reminderId.value}" }
            return Outcome.NotFound
        }

        post(reminder, userId)

        if (ReminderFireLogic.shouldDeleteAfterFire(reminder)) {
            // `runCatchingCancellable`, not `runCatching`: the latter also swallows
            // CancellationException, which would let a cancelled delete report success.
            runCatchingCancellable { reminderRepo.delete(reminderId, userId) }
                .onFailure { logger.w { "Failed to delete reminder ${reminderId.value}: ${it.message}" } }
        }
        return Outcome.Posted
    }

    /**
     * Fire a [Reminder] whose row the caller already has.
     *
     * For the Android boot catch-up, which enumerates rows rather than looking each one
     * up: re-reading each row through [ReminderRepository.get] would filter it by the
     * active profile and drop every reminder belonging to another profile from a batch
     * that was explicitly built to be profile-complete.
     *
     * The firing profile is [reminder]'s own [Reminder.userId], because the caller
     * enumerated by owner and has no other profile to charge it to.
     */
    suspend fun fireKnown(reminder: Reminder): Outcome {
        if (!notifier.isSupported) {
            logger.w { "No notifier on this host; reminder ${reminder.id.value} cannot be shown" }
            return Outcome.NoNotifier
        }

        post(reminder, reminder.userId)

        if (ReminderFireLogic.shouldDeleteAfterFire(reminder)) {
            runCatchingCancellable { reminderRepo.delete(reminder.id, reminder.userId) }
                .onFailure { logger.w { "Failed to delete reminder ${reminder.id.value}: ${it.message}" } }
        }
        return Outcome.Posted
    }

    /**
     * The shared half of both entry points.
     *
     * The title is read **now**, not remembered from arm time — that is the entire reason
     * [ReminderFireLogic] exists, and the reason the Desktop backend runs a launcher that
     * opens the database instead of printing a title baked into a systemd unit.
     */
    private suspend fun post(reminder: Reminder, userId: UserId) {
        val taskTitle = taskRepo.get(reminder.taskId)?.title
        val outcome = ReminderFireLogic.execute(reminder, taskTitle)
        notifier.post(
            AlarmContract.tagFor(userId, reminder.id),
            outcome.title,
            outcome.body,
            reminder.viewId?.raw,
        )
    }
}

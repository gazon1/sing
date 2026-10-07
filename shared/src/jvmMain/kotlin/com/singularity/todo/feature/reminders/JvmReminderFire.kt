package com.singularity.todo.feature.reminders

import co.touchlab.kermit.Logger
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.notifications.Notifier
import com.singularity.todo.feature.alarms.AlarmContract
import com.singularity.todo.feature.reminders.domain.port.ReminderRepository
import com.singularity.todo.feature.tasks.domain.port.TaskRepository

/**
 * Fires one Desktop reminder, from a process that exists only to do that.
 *
 * ## Why this is an object and not a DI binding
 *
 * It deliberately takes its collaborators as parameters instead of resolving them from
 * the graph. A Koin binding would have to live in `PlatformModule.jvm.kt` — it is JVM-only,
 * because Android fires the same reminder through `AlarmReceiver` — and every typed
 * binding in a platform module has to appear in `platform-seams.tsv`. This class is not a
 * platform *seam*: Android never binds it and does not need it. Registering a row for
 * something that only exists on one side would be a lie in the one file whose whole
 * purpose is to say which things genuinely diverge between platforms.
 *
 * `desktopApp`'s `main` resolves the three ports from the graph and passes them in.
 *
 * ## Why the title is read here and not baked into the systemd unit
 *
 * A `systemd-run` transient unit is a fixed command line with no database access. The
 * obvious shortcut — put the task title in the unit and let `notify-send` print it — is
 * what makes this a headless entry point instead of three lines of shell. It is avoided
 * for the reason [ReminderFireLogic] exists: a task renamed between arming and firing
 * would otherwise be announced under its old name, which is the exact bug the Android
 * fire path was written to eliminate.
 *
 * The second reason is quieter and just as binding: a task title is user-authored text,
 * and systemd's command line has its own escaping rules that are not a shell's and not
 * `ProcessBuilder`'s. Reading the title here means the only place it becomes an argument
 * is [Notifier.post], which builds an argv list and never a command string.
 */
object JvmReminderFire {

    private val logger = Logger.withTag("JvmReminderFire")

    /** What happened when a reminder was fired. */
    enum class Outcome {
        /** The notification was handed to the notifier. */
        Posted,

        /** No such reminder. Either it was deleted already, or it belongs to another profile. */
        NotFound,

        /**
         * The host cannot display a notification.
         *
         * A distinct outcome rather than a silent `Posted`, because a reminder that was
         * armed and then could not be shown is the one case where the user is worse off
         * than if the reminder had never been created — and it is diagnosable only if the
         * unit logs it.
         */
        NoNotifier,
    }

    /**
     * Fire the reminder identified by [reminderId] on behalf of [userId].
     *
     * Mirrors `AlarmReceiver.handleReminderFire` step for step, deliberately: same row
     * lookup, same fresh title read, same tag, same delete-one-shots decision. The two
     * platforms' reminder behaviour must not diverge in the detail, and the cheapest way
     * to guarantee that is to have them run the same logic over the same data.
     *
     * A failure to delete is logged and does not change the outcome: the notification was
     * shown, which is what the user was promised. Leaving the row behind means the
     * reminder fires again, which is recoverable; withholding the notification is not.
     */
    suspend fun fire(
        reminderRepo: ReminderRepository,
        taskRepo: TaskRepository,
        notifier: Notifier,
        reminderId: ReminderId,
        userId: UserId,
    ): Outcome {
        if (!notifier.isSupported) {
            logger.w { "No notifier on this host; reminder ${reminderId.value} cannot be shown" }
            return Outcome.NoNotifier
        }

        val reminder = reminderRepo.get(reminderId) ?: run {
            logger.w { "Reminder not found: ${reminderId.value}" }
            return Outcome.NotFound
        }

        // Fresh title, read at fire time — see the class KDoc.
        val taskTitle = taskRepo.get(reminder.taskId)?.title
        val outcome = ReminderFireLogic.execute(reminder, taskTitle)
        notifier.post(
            AlarmContract.tagFor(userId, reminderId),
            outcome.title,
            outcome.body,
            reminder.viewId?.raw,
        )

        if (outcome.shouldDelete) {
            reminderRepo.delete(reminderId, userId)
                .onFailure { logger.w { "Failed to delete reminder ${reminderId.value}: ${it.message}" } }
        }
        return Outcome.Posted
    }
}

/**
 * The command-line form of [JvmReminderFire], parsed without starting anything.
 *
 * Split out from the desktop entry point so the parsing is testable without a Koin graph,
 * a database, or a display — and so the argv contract the systemd unit depends on has a
 * test of its own rather than being asserted only through a manual `systemd-run`.
 *
 * ## Shape
 *
 * ```
 * singularity-todo fire-reminder <reminderId> <userId>
 * ```
 *
 * The launcher binary is the packaged Desktop app itself, not a second artifact: a
 * reminder that needs a helper binary installed next to it is a reminder that silently
 * does not fire on every machine where the helper was forgotten. `main` returns before
 * `singleWindowApplication`, so no window or AWT toolkit is initialised.
 */
object JvmReminderFireCommand {

    const val COMMAND = "fire-reminder"

    /** A parsed, validated `fire-reminder` invocation. */
    data class Request(val reminderId: ReminderId, val userId: UserId)

    /**
     * Parse [args], or return null when [args] is not a fire request.
     *
     * Null rather than an exception, because "not a fire request" is the overwhelmingly
     * common case: every ordinary launch arrives here too, with no arguments at all.
     */
    fun parse(args: Array<String>): Request? {
        if (args.firstOrNull() != COMMAND) return null
        val reminderId = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        val userId = args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return null
        return Request(ReminderId(reminderId), UserId(userId))
    }
}

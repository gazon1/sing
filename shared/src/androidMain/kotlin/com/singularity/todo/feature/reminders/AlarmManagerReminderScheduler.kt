package com.singularity.todo.feature.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.singularity.todo.feature.alarms.AlarmContract
import com.singularity.todo.feature.alarms.AlarmReceiver
import com.singularity.todo.feature.reminders.domain.port.ReminderRepository
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/**
 * Android [ReminderScheduler] implementation using [AlarmManager.setAlarmClock].
 *
 * Uses `setAlarmClock` which is:
 * - exempt from Doze App Standby
 * - exempt from `SCHEDULE_EXACT_ALARM` permission checks (no `<uses-permission>` needed)
 * - guaranteed to fire even if the device is in deep sleep
 *
 * ## Alarm key format
 * All alarms are keyed by `"reminder:${userId.value}:${id.value}"` to ensure
 * cross-profile isolation.
 *
 * ## Stale-text fix
 * The notification title/body are **not** passed via PendingIntent extras.
 * Instead, [AlarmReceiver.handleReminderFire] reads the fresh task title from Room DB
 * at fire time. This prevents showing stale task names when the user renamed a task
 * after scheduling the reminder.
 *
 * @param context Android [Context] (application or activity scoped).
 * @param clock Injected [Clock] for testability.
 * @param reminderRepo [ReminderRepository] — used by [cancelByTask] to enumerate all reminders for a task.
 */
class AlarmManagerReminderScheduler(
    private val context: Context,
    private val clock: Clock,
    private val reminderRepo: ReminderRepository,
) : ReminderScheduler {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override suspend fun schedule(reminder: Reminder) {
        if (reminder.fireAt <= clock.now().toEpochMilliseconds()) return
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(reminder.fireAt, null),
            buildPending(reminder),
        )
    }

    override suspend fun cancel(id: ReminderId, userId: com.singularity.todo.core.ids.UserId) {
        alarmManager.cancel(buildPending(id, userId, includeExtras = false))
    }

    override suspend fun cancelByTask(taskId: TaskId, userId: com.singularity.todo.core.ids.UserId) {
        reminderRepo.watchByTask(taskId).first()
            .filter { it.userId == userId }
            .forEach { cancel(it.id, it.userId) }
    }

    // ─── PendingIntent builders ─────────────────────────────────────────────────

    private fun buildPending(reminder: Reminder): PendingIntent =
        buildPending(reminder.id, reminder.userId, includeExtras = true)

    private fun buildPending(
        id: ReminderId,
        userId: com.singularity.todo.core.ids.UserId,
        includeExtras: Boolean,
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_REMINDER_FIRE
            if (includeExtras) {
                putExtra(AlarmContract.EXTRA_REMINDER_ID, id.value)
                putExtra(AlarmContract.EXTRA_USER_ID, userId.value)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            AlarmContract.tagFor(userId, id).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

package com.singularity.todo.feature.alarms

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.pomodoro.PomodoroPhase
import com.singularity.todo.feature.reminders.ReminderId

/**
 * Shared constants and utilities for the alarm subsystem.
 *
 * All alarm keys use the format `"reminder:${userId.value}:${id.value}"` to ensure
 * cross-profile isolation.
 *
 * ## Intent extras
 * [EXTRA_REMINDER_ID], [EXTRA_USER_ID], [EXTRA_PHASE], [EXTRA_TASK_ID] are used by
 * [com.singularity.todo.feature.alarms.AlarmReceiver] and
 * [com.singularity.todo.feature.reminders.AlarmManagerReminderScheduler].
 *
 * ## Phase encoding
 * [PHASE_WORK], [PHASE_SHORT_BREAK], [PHASE_LONG_BREAK] are the string values written to
 * Intent extras and must match [PomodoroPhase.name] values (Work, ShortBreak, LongBreak).
 */
object AlarmContract {

    /**
     * Builds the alarm tag for a reminder.
     * Used as the PendingIntent key to ensure one alarm per (user, reminder) pair.
     */
    fun tagFor(userId: UserId, id: ReminderId): String = "reminder:${userId.value}:${id.value}"

    // ─── Intent extra keys ──────────────────────────────────────────────────────

    const val EXTRA_REMINDER_ID = "reminder_id"
    const val EXTRA_USER_ID = "user_id"
    const val EXTRA_PHASE = "phase"
    const val EXTRA_TASK_ID = "pomodoro_task_id"

    // ─── Pomodoro phase string values ──────────────────────────────────────────
    // These must match PomodoroPhase.name values (Work, ShortBreak, LongBreak).
    // Written to Intent extras by PomodoroAlarmScheduler and read by AlarmReceiver.

    const val PHASE_WORK = "Work"
    const val PHASE_SHORT_BREAK = "ShortBreak"
    const val PHASE_LONG_BREAK = "LongBreak"
}

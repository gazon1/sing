package com.singularity.todo.feature.reminders

import com.singularity.todo.core.database.TaskReminderEntity
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * Reminder type distinguishes the intensity of the notification.
 *
 * - [Gentle]: single notification, unobtrusive
 * - [Annoying]: repeats, escalates in urgency
 */
enum class ReminderType { Gentle, Annoying }

/** Read model exposed to UI. */
data class Reminder(
    val id: ReminderId,
    val taskId: TaskId,
    val userId: UserId,
    val type: ReminderType,
    /** Minutes before (negative) or after the due datetime. */
    val offsetMinutes: Int,
    /** Epoch millis when this reminder should fire. */
    val fireAt: Long,
    /** Cron expression for recurring reminders, null for one-shot. */
    val recurringPattern: String?,
)

/** Type-safe ID wrapper. */
@JvmInline
value class ReminderId(val value: String) {
    companion object {
        fun generate(): ReminderId = ReminderId(com.singularity.todo.core.ids.nextId())
    }
}

// ─── Mapping ─────────────────────────────────────────────────────────────────

fun TaskReminderEntity.toReminder() = Reminder(
    id = ReminderId(id),
    taskId = TaskId(taskId),
    userId = UserId(userId),
    type = when (type) {
        "annoying" -> ReminderType.Annoying
        else -> ReminderType.Gentle
    },
    offsetMinutes = offsetMinutes,
    fireAt = fireAt,
    recurringPattern = recurringPattern,
)

fun Reminder.toEntity(now: Long) = TaskReminderEntity(
    id = id.value,
    taskId = taskId.value,
    userId = userId.value,
    type = type.name.lowercase(),
    offsetMinutes = offsetMinutes,
    fireAt = fireAt,
    recurringPattern = recurringPattern,
    createdAt = now,
    updatedAt = now,
)

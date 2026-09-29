package com.singularity.todo.feature.reminders

import com.singularity.todo.core.database.ProjectReminderEntity
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.ids.nextId
import com.singularity.todo.feature.projects.domain.model.ProjectId

/**
 * A reminder that fires for a whole project rather than a single task.
 *
 * ## Why its own table instead of a nullable `task_reminders.task_id`
 *
 * `task_reminders.task_id` is non-nullable, and a project reminder has no task. Making
 * the column nullable would mean rebuilding the table in a migration (SQLite cannot
 * relax `NOT NULL` in place) and would leave the name `TaskReminderEntity` describing
 * a type that is half task and half project. Every read would then have to decide
 * whether a null `task_id` was legitimate or corrupt.
 *
 * A separate table costs one migration and keeps both models honest. The cost is that
 * the fire path has to handle two tables — see [ProjectReminderScheduler].
 *
 * ## Semantics
 *
 * A project reminder fires once, at [fireAt]. It does not repeat and it does not
 * escalate, so unlike [Reminder] there is no [ReminderType] and no recurring pattern —
 * a field that is always null is a field that will eventually be read as meaningful.
 * Fire on project due date is a real product decision, not an omission.
 */
data class ProjectReminder(
    val id: ProjectReminderId,
    val projectId: ProjectId,
    val userId: UserId,
    /** Epoch millis when this reminder should fire. */
    val fireAt: Long,
    /** Epoch millis of the last successful fire; guards against a re-fire after a reboot. */
    val lastFiredAt: Long? = null,
)

/** Type-safe ID wrapper for [ProjectReminder]. */
@JvmInline
value class ProjectReminderId(val value: String) {
    companion object {
        fun generate(): ProjectReminderId = ProjectReminderId(nextId())
    }
}

// ─── Mapping ─────────────────────────────────────────────────────────────────

fun ProjectReminderEntity.toProjectReminder() = ProjectReminder(
    id = ProjectReminderId(id),
    projectId = ProjectId(projectId),
    userId = UserId(userId),
    fireAt = fireAt,
    lastFiredAt = lastFiredAt,
)

fun ProjectReminder.toEntity(now: Long) = ProjectReminderEntity(
    id = id.value,
    projectId = projectId.value,
    userId = userId.value,
    fireAt = fireAt,
    lastFiredAt = lastFiredAt,
    createdAt = now,
    updatedAt = now,
)

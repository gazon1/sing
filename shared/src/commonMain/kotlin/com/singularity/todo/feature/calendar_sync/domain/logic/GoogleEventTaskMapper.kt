package com.singularity.todo.feature.calendar_sync.domain.logic

import com.singularity.todo.feature.calendar_sync.domain.model.GoogleEvent
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The task fields a Google event maps onto, before any id or timestamp is attached.
 *
 * A separate type from [Task] so the mapping is a pure function of an event and a time
 * zone, and so a test can assert the interesting cases — an all-day event, a cancelled
 * event, an event with no start — without constructing a [Task] with an id, a user and two
 * clock readings it does not care about.
 */
data class TaskDraft(
    val title: String,
    val description: String? = null,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val endDate: LocalDate? = null,
    val endTime: LocalTime? = null,
)

/**
 * Translates between Google events and the fields of a [Task].
 *
 * ## What does not map, and why that is not a bug
 *
 * **Location.** [Task] has no location field. The event's location is therefore kept on
 * the Google side only and is never written to the task, and — this is the part that
 * matters — the push path takes its location from the last-agreed shadow rather than from
 * the task. So a task that has no location does not cause the next push to blank the
 * location on a real calendar entry. Widening [Task] to carry a location is a product
 * decision, not a sync decision, and it is not taken here.
 *
 * **Recurrence.** Google's `RRULE` is stored and re-sent byte-identical, verbatim, and is
 * deliberately never parsed. [Task.recurrence] is a `RecurrenceSpec`, so a faithful
 * translation would mean writing an RFC 5545 parser, and a lossy one would silently change
 * how many occurrences a series has. The rule therefore stays in the event and out of the
 * task; the same verbatim rule round-trips unchanged, which is the property that matters
 * when a user's real calendar is the other end.
 *
 * **All-day events** get a due *date* and no due *time*. The app's model treats a time as
 * the precise part and a date as the coarse part; inventing `00:00` for an all-day event
 * would make it sort and remind as if it were a midnight appointment.
 */
object GoogleEventTaskMapper {

    /**
     * The title an event with [title] should give its task.
     *
     * Google permits an empty title, and `Task.title` is not nullable. An untitled event
     * would otherwise fail task creation with a validation error the user cannot act on, so
     * it gets a name that says what it is. Kept short deliberately: it is a label for a
     * real appointment, not a sentence.
     */
    const val UNTITLED = "(untitled event)"

    /**
     * The task fields [event] contributes, read in [timeZone].
     *
     * Returns null for a cancelled event: there is no task to describe, and a cancelled
     * event must never become a task the user then has to delete by hand.
     */
    fun draftFor(event: GoogleEvent, timeZone: TimeZone): TaskDraft? {
        if (!event.isLive) return null

        val start = event.startsAt?.toLocalDateTime(timeZone)
        val end = event.endsAt?.toLocalDateTime(timeZone)

        return TaskDraft(
            title = event.title?.takeIf { it.isNotBlank() } ?: UNTITLED,
            description = event.description?.takeIf { it.isNotBlank() },
            dueDate = start?.date,
            // An all-day event has a date, not a moment. See the class comment.
            dueTime = if (event.allDay == true) null else start?.time,
            endDate = end?.date,
            endTime = if (event.allDay == true) null else end?.time,
        )
    }

    /**
     * A [Task] for [event], for the case where the event has never had one.
     *
     * Returns null when [event] cannot become a task — cancelled, or carrying no start, so
     * there is nothing to schedule.
     */
    fun newTask(
        event: GoogleEvent,
        taskId: TaskId,
        userId: com.singularity.todo.core.ids.UserId,
        now: Instant,
        timeZone: TimeZone,
    ): Task? {
        val draft = draftFor(event, timeZone) ?: return null
        // An event with no start becomes an unscheduled task rather than nothing: the user
        // can still see it, rename it, and give it a date. Dropping it would lose a real
        // appointment that Google simply did not put a time on.
        return Task(
            id = taskId,
            title = draft.title,
            description = draft.description,
            dueDate = draft.dueDate,
            dueTime = draft.dueTime,
            endDate = draft.endDate,
            endTime = draft.endTime,
            createdAt = now,
            updatedAt = now,
            userId = userId,
        )
    }

    /**
     * The task fields a merge asked to change, and the values to change them to.
     *
     * [changed] is separate from the values on purpose. A remote value may legitimately be
     * null — the user cleared the description on their laptop — and a type that carried only
     * nullable values could not tell "clear the description" from "leave the description
     * alone". Those are opposite instructions, and picking the wrong one either erases a
     * field the user still wants or silently refuses an edit they made elsewhere. So the
     * *set* says which fields move, and the values say where they go.
     */
    data class TaskEdits(
        val changed: Set<TaskField> = emptySet(),
        val title: String? = null,
        val description: String? = null,
        val dueDate: LocalDate? = null,
        val dueTime: LocalTime? = null,
        val endDate: LocalDate? = null,
        val endTime: LocalTime? = null,
    ) {
        /** True when the merge asked for no change at all. */
        val isEmpty: Boolean get() = changed.isEmpty()

        /**
         * Whether the edits move the task in time.
         *
         * Separate from [isEmpty] because moving a task is the only edit that drags its
         * reminders along with it. A rename must not reschedule a notification — a user who
         * renames a task would otherwise get a reminder moved by the length of the new name.
         */
        val movesTime: Boolean get() = changed.any { it in TIME_FIELDS }

        private companion object {
            val TIME_FIELDS = setOf(TaskField.DueDate, TaskField.DueTime, TaskField.EndDate, TaskField.EndTime)
        }
    }

    /** A task field a [TaskEdits] can move. */
    enum class TaskField { Title, Description, DueDate, DueTime, EndDate, EndTime }

    /**
     * The edits implied by [merge] against the remote event, read in [timeZone].
     *
     * Only [FieldOutcome.ApplyToTask] contributes. A [FieldOutcome.Push] is the app's own
     * value on its way to Google, a [FieldOutcome.Conflict] keeps what the app already has
     * by decision, and a [FieldOutcome.NoOp] means the two sides already agree. Applying a
     * push or a conflict here would overwrite the user's own edit with the remote value the
     * merge deliberately kept — the exact inverse of the merge's decision.
     */
    fun editsFor(
        merge: MergeResult,
        remote: GoogleEvent,
        timeZone: TimeZone,
    ): TaskEdits {
        // Read through MergeResult's named accessors rather than by position: `fields` is
        // indexed against EventShadow's declaration order, and a raw index here would
        // silently mislabel every outcome after a field added to one list and not the
        // other. See MergeResult.
        val title = merge.title as? FieldOutcome.ApplyToTask
        val description = merge.description as? FieldOutcome.ApplyToTask
        val start = merge.startsAt as? FieldOutcome.ApplyToTask
        val end = merge.endsAt as? FieldOutcome.ApplyToTask
        // Counted rather than chained, so adding a task-mapped field later cannot quietly
        // push this guard past the complexity threshold and fail detekt for a reason that
        // reads like noise.
        if (listOfNotNull(title, description, start, end).isEmpty()) return TaskEdits()

        // Only the time fields are dropped for an all-day event; `merge.startsAt`/`endsAt`
        // are consulted on their own terms so an all-day event still moves its dates.
        val startLocal = (start?.value as? Instant)?.toLocalDateTime(timeZone)
        val endLocal = (end?.value as? Instant)?.toLocalDateTime(timeZone)
        val isAllDay = remote.allDay == true

        return TaskEdits(
            changed = buildSet {
                if (title != null) add(TaskField.Title)
                if (description != null) add(TaskField.Description)
                if (start != null) {
                    add(TaskField.DueDate)
                    add(TaskField.DueTime)
                }
                if (end != null) {
                    add(TaskField.EndDate)
                    add(TaskField.EndTime)
                }
            },
            title = title?.value as? String,
            description = description?.value as? String,
            dueDate = startLocal?.date,
            dueTime = if (isAllDay) null else startLocal?.time,
            endDate = endLocal?.date,
            endTime = if (isAllDay) null else endLocal?.time,
        )
    }
}

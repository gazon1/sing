package com.singularity.todo.feature.tasks.domain.state

import com.singularity.todo.core.ui.MviIntent
import com.singularity.todo.feature.timetracking.domain.TimeEntryKind

sealed interface TaskDetailSlotIntent : MviIntent

sealed interface TaskEntityIntent : TaskDetailSlotIntent

/**
 * The intents that write the tags of the task.
 *
 * A group of its own, because the three of them are one decision — what the tag
 * set of the task is — read, written and put back. The entity slot handles them
 * in one place, and the type is what lets that place be an exhaustive `when`.
 */
sealed interface TaskTagIntent : TaskEntityIntent

/**
 * The intents that write *when* the work happens: the due date and its time,
 * the start date and its time, the deadline and the `someday` bucket.
 *
 * A group of its own, because the facts are one decision — when this task is
 * due — and because the type is what lets the entity slot handle them in one
 * place with an exhaustive `when`.
 */
sealed interface TaskSchedulingIntent : TaskEntityIntent

sealed interface TaskDraftIntent : TaskDetailSlotIntent

sealed interface TaskCompletionIntent : TaskDetailSlotIntent

sealed interface TaskChildrenIntent : TaskDetailSlotIntent

sealed interface TaskRemindersIntent : TaskDetailSlotIntent

sealed interface TaskLifecycleIntent : TaskDetailSlotIntent

sealed interface TaskAiIntent : TaskDetailSlotIntent

/**
 * The two things the link preview card can be asked to do.
 *
 * [Refresh] is the screen saying "this address is on screen now", and the slot answers
 * it by reading the cache — a fetch happens only when the cache has nothing recent.
 * [Hide] dismisses the card for the address, and touches no task.
 */
sealed interface TaskLinkPreviewIntent : TaskDetailSlotIntent

sealed interface TaskTimeSlotIntent : TaskDetailSlotIntent {
    data object Start : TaskTimeSlotIntent

    data object Stop : TaskTimeSlotIntent

    data class CreateManual(val startedAtMs: Long, val endedAtMs: Long, val kind: TimeEntryKind, val note: String?) :
        TaskTimeSlotIntent

    data class Tick(val elapsedMs: Long) : TaskTimeSlotIntent
}

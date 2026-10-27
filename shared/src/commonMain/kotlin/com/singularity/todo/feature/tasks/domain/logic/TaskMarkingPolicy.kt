package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskMarking
import com.singularity.todo.feature.tasks.domain.model.TaskWorkflowState
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.datetime.LocalDate

/**
 * The markings that apply to a task.
 *
 * A pure function of the task, the reference date and the set of blocked task
 * ids — so it can be tested without a database, a clock or a ViewModel, and
 * every surface that shows a task gets the same answer.
 *
 * ## Why blocked-ness is passed in as a fact
 *
 * "This task is blocked" is not a fact about the task alone: it is a fact about
 * the task *and the tasks it waits for*, because a prerequisite that has been
 * finished no longer blocks anything. The answer is therefore computed once for
 * the whole list — [Blocking.blockedTaskIds] — and handed to this function as
 * the fact it already is. Rebuilding the answer per row is what made the old
 * path quadratic in the task count.
 *
 * ## Why the follow-up comes from the task
 *
 * A task that follows up on another is not waiting for it, so the two
 * conditions cannot both come from the same relation. The blocking edges live
 * in [Task.blockedBy]; the follow-up edges live in [Task.followsUp], and they
 * are read from the same cross-reference table by a different question.
 *
 * @param task the task to mark.
 * @param today the reference date for the overdue marking.
 * @param isBlocked whether the task waits for something unfinished.
 * @return every marking that applies to [task].
 */
fun computeTaskMarkings(
    task: Task,
    today: LocalDate,
    isBlocked: Boolean = false,
): ImmutableSet<TaskMarking> {
    val markings = mutableSetOf<TaskMarking>()

    if (isBlocked) markings += TaskMarking.Blocked
    if (task.followsUp.isNotEmpty()) markings += TaskMarking.FollowUp

    when (task.workflowState) {
        TaskWorkflowState.Open -> Unit
        TaskWorkflowState.NextUp -> markings += TaskMarking.NextUp
        TaskWorkflowState.InProgress -> markings += TaskMarking.InProgress
    }

    if (task.completedAt != null) {
        markings += TaskMarking.Completed
    } else {
        val dueDate = task.dueDate
        if (dueDate == null) {
            markings += TaskMarking.NoDate
        } else if (TaskComputed.isOverdue(task, today)) {
            // The overdue rule has one owner — TaskComputed.isOverdue — so that
            // the marking and the predicate cannot answer differently.
            markings += TaskMarking.Overdue
        }
    }

    if (task.isPinned) markings += TaskMarking.Pinned
    if (task.recurrence != null) markings += TaskMarking.Recurring

    return markings.toImmutableSet()
}

/**
 * The order the markings are shown in.
 *
 * The order puts the two **stored** states first — they are the ones the user
 * set, so they are the ones the user is looking for — then the **blocking**
 * facts, which change what the user can do next, then the scheduling facts,
 * then the decorative ones.
 *
 * It is a function of the marking rather than of the row, so two rows with the
 * same markings look the same.
 */
val TaskMarking.displayOrder: Int
    get() = when (this) {
        TaskMarking.InProgress -> 0
        TaskMarking.NextUp -> 1
        TaskMarking.Blocked -> 2
        TaskMarking.FollowUp -> 3
        TaskMarking.Overdue -> 4
        TaskMarking.NoDate -> 5
        TaskMarking.Pinned -> 6
        TaskMarking.Recurring -> 7
        TaskMarking.Completed -> 8
    }

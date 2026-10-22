package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * The prerequisites that are still standing in the way of a task.
 *
 * ## What counts
 *
 * A prerequisite is **active** when it blocks the task, still exists, is not
 * finished, and has not been discarded. The list is returned rather than a
 * yes/no answer because the caller has to *name* the blockers: a confirmation
 * that says "this task is blocked" without saying what by is a question the
 * user cannot answer.
 *
 * ## Why this is not a use case
 *
 * The function asks a question and does not answer it with a write. The
 * decision of what to do with the answer belongs to the view model, and the
 * write belongs to the path the surface already used — which differs between
 * the agenda (a SQL toggle) and the task screen (a copy-and-write, with a
 * separate branch for a repeating task). A use case that only answered "may I?"
 * would carry a repository dependency, a DI binding and a coroutine, and would
 * still not perform the write.
 *
 * ## Consistency
 *
 * The semantics are the same as [TaskComputed.isBlocked] and
 * [TaskComputed.blockedIds]; what is added here is *which* prerequisites, not
 * *whether* there are any. [activeBlockersByTask] is the batch form of the same
 * rule and is the one to reach for when the answer is needed for a whole list.
 *
 * @see com.singularity.todo.feature.tasks.domain.model.Task.dependsOn
 * @see TaskComputed.isBlocked
 */
fun activeBlockers(task: Task, allTasks: List<Task>): List<Task> {
    if (task.dependsOn.isEmpty()) return emptyList()
    val byId = allTasks.associateBy { it.id }
    return task.dependsOn
        .mapNotNull { byId[it] }
        .filter { !it.isCompleted && !it.isTrashed }
}

/**
 * The active prerequisites of every task in [allTasks], indexed by the task
 * they block.
 *
 * One index for the whole list, so the cost is O(n + edges) rather than the
 * quadratic cost of calling [activeBlockers] per task — the same reason
 * [TaskComputed.blockedIds] exists. A task with no active prerequisites is
 * absent from the map rather than mapped to an empty list, so "is this task
 * blocked" is a map lookup rather than a scan.
 */
fun activeBlockersByTask(allTasks: List<Task>): Map<TaskId, List<Task>> {
    if (allTasks.none { it.dependsOn.isNotEmpty() }) return emptyMap()
    val byId = allTasks.associateBy { it.id }
    val blockers = mutableMapOf<TaskId, List<Task>>()
    allTasks.forEach { task ->
        if (task.dependsOn.isEmpty()) return@forEach
        val active = task.dependsOn
            .mapNotNull { byId[it] }
            .filter { !it.isCompleted && !it.isTrashed }
        if (active.isNotEmpty()) {
            blockers[task.id] = active
        }
    }
    return blockers
}

package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId

/**
 * The one rule that decides whether a task is blocked.
 *
 * ## Why this object exists
 *
 * The rule used to live in two places at once: [TaskComputed.isBlocked] and
 * [TaskComputed.blockedIds] asked "is this task blocked", and the confirmation
 * dialog asked "by what". Both walked the same edges and both decided the
 * same thing — a prerequisite that exists, is not finished and has not been
 * discarded is in the way — and they were free to drift apart, which is
 * exactly what happened once: the badge asked whether the dependency set was
 * empty, the rule asked whether the prerequisites were finished. A user saw
 * "blocked" on one screen and was asked to confirm on another.
 *
 * So there is one owner now. Everything that needs the answer comes here, in
 * one of four forms, and the forms differ only in what they return:
 *
 * | Question | Answer |
 * |---|---|
 * | is this one task blocked? | [isBlocked] |
 * | by what? | [activeBlockers] |
 * | which tasks in this list are blocked? | [blockedTaskIds] |
 * | which tasks in this list are blocked, and by what? | [activeBlockersByTask] |
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
 * @see com.singularity.todo.feature.tasks.domain.model.Task.blockedBy
 */
object Blocking {

    /**
     * The prerequisites that are still standing in the way of [task].
     *
     * @param task the task whose prerequisites are asked about.
     * @param allTasks every task of the current user — the list the prerequisites
     *   are resolved against. A prerequisite missing from it is not a blocker.
     */
    fun activeBlockers(task: Task, allTasks: List<Task>): List<Task> {
        if (task.blockedBy.isEmpty()) return emptyList()
        val byId = allTasks.associateBy { it.id }
        return task.blockedBy
            .mapNotNull { byId[it] }
            .filter { !it.isCompleted && !it.isTrashed }
    }

    /**
     * The active prerequisites of every task in [allTasks], indexed by the task
     * they block.
     *
     * One index for the whole list, so the cost is O(n + edges) rather than the
     * quadratic cost of calling [activeBlockers] per task. A task with no active
     * prerequisites is absent from the map rather than mapped to an empty list,
     * so "is this task blocked" is a map lookup rather than a scan.
     *
     * @param allTasks every task of the current user.
     */
    fun activeBlockersByTask(allTasks: List<Task>): Map<TaskId, List<Task>> {
        if (allTasks.none { it.blockedBy.isNotEmpty() }) return emptyMap()
        val byId = allTasks.associateBy { it.id }
        val blockers = mutableMapOf<TaskId, List<Task>>()
        allTasks.forEach { task ->
            if (task.blockedBy.isEmpty()) return@forEach
            val active = task.blockedBy
                .mapNotNull { byId[it] }
                .filter { !it.isCompleted && !it.isTrashed }
            if (active.isNotEmpty()) {
                blockers[task.id] = active
            }
        }
        return blockers
    }

    /**
     * Whether [task] is blocked — the yes/no form of [activeBlockers].
     *
     * @param task the task to judge.
     * @param allTasks every task of the current user.
     */
    fun isBlocked(task: Task, allTasks: List<Task>): Boolean =
        Blocking.activeBlockers(task, allTasks).isNotEmpty()

    /**
     * The ids of every blocked task in [allTasks].
     *
     * The batch form of [isBlocked]: identical semantics, but O(n + edges)
     * instead of O(n²), because the lookup table is built once rather than once
     * per task.
     *
     * [isBlocked] rebuilds that index on **every call**, so calling it once per
     * task over a list of *n* is quadratic. Measured on this repo's test
     * hardware: ~90ms at n=1000, ~300ms at n=2000, ~1.1s at n=4000 — a visible
     * stall, since the agenda evaluator calls it twice per rendered row. Reach
     * for this instead whenever the answer is needed for a whole list.
     *
     * A prerequisite missing from [allTasks] does **not** block, matching
     * [isBlocked].
     *
     * @param allTasks every task of the current user.
     */
    fun blockedTaskIds(allTasks: List<Task>): Set<TaskId> =
        Blocking.activeBlockersByTask(allTasks).keys
}

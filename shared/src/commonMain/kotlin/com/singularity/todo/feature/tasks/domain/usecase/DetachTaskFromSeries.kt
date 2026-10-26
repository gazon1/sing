package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository

/**
 * Takes one occurrence out of its series.
 *
 * ## Why this exists
 *
 * A series says "this is one piece of work, on several dates". Sometimes that
 * turns out to be wrong for one of the dates: the thing on Friday is a
 * different job that happened to be written next to the others. There is no
 * way to say that while the row carries the series id — the row keeps saying
 * it is part of a work it is not part of.
 *
 * So the occurrence leaves: it becomes a task that stands alone, and it keeps
 * its date, its time and everything that belongs to the occurrence. What it
 * does not keep is the claim that it is one of several.
 *
 * ## The rule about the last one
 *
 * A series of one member is not a series — there is nothing for the id to
 * point at. So when the occurrence that leaves was the second of two, the
 * one that stays also stands alone: the series ends where it ends, and both
 * rows are ordinary tasks afterwards.
 *
 * ## The rule goes with the series id
 *
 * An occurrence that leaves a **recurring** series loses the rule as well as the
 * series id. A row that kept the rule would carry the repeat badge and never
 * repeat — a promise nothing keeps. The other members keep the rule, and the
 * series continues from what remains.
 *
 * The row that stays behind when the series ends loses the rule too: a rule on a
 * row that stands alone is the exact shape the launch repair adopts into a new
 * series, so a rule that stayed would make the detach undo itself at the next
 * launch.
 */
class DetachTaskFromSeriesUseCase(private val repo: TaskRepository) {

    /**
     * Detaches the task [taskId] from its series.
     *
     * @return the detached task, or a failure. A task that is not part of a
     *   series is returned unchanged — it already stands alone, and saying so
     *   is the truth, not an error.
     */
    suspend operator fun invoke(taskId: TaskId): Result<Task> {
        val task = repo.get(taskId)
            ?: return Result.failure(IllegalStateException("Task $taskId not found"))
        val seriesId = task.seriesId
            ?: return Result.success(task)

        val members = repo.getSeries(seriesId)
        val remaining = members.filter { it.id != taskId }
        val recurring = members.any { it.recurrence != null }

        // A row that leaves a recurring series loses the rule with the series id.
        val detached = if (recurring) task.copy(seriesId = null, recurrence = null) else task.copy(seriesId = null)
        repo.update(detached).getOrThrow()

        // The last other member stands alone as well: a series of one is not a
        // series, and leaving the id on it would promise a group of one. A
        // recurring member also loses the rule — otherwise the launch repair
        // would adopt it back.
        if (remaining.size == 1) {
            val last = remaining.single()
            val standing = if (recurring) last.copy(seriesId = null, recurrence = null) else last.copy(seriesId = null)
            repo.update(standing).getOrThrow()
        }

        return Result.success(detached)
    }
}

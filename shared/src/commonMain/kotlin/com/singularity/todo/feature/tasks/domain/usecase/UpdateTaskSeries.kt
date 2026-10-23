package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository

/**
 * Writes one change to every member of a series.
 *
 * ## Why this exists
 *
 * A series is several rows that stand for one piece of work. Editing the title
 * of one of them has to change the title of all of them — otherwise the series
 * says two different things about the same job, and there is no rule that says
 * which one is right.
 *
 * ## Why it is not pass-through
 *
 * The use case reads the members, applies the change to each, and writes them
 * back in one transaction. The read is what makes it more than a rename of
 * `updateAll`: the members are the ones in the database *now*, not a snapshot
 * the screen happened to be holding.
 *
 * ## What it does not do
 *
 * It does not decide *which* fields are shared — that rule lives in
 * [com.singularity.todo.feature.tasks.domain.model.TaskSharedField] and is
 * applied by the caller. This use case only knows how to write a series.
 */
class UpdateTaskSeriesUseCase(private val repo: TaskRepository) {
    /**
     * Applies [transform] to every member of [seriesId] and writes them in one
     * transaction.
     *
     * @return the written members, or a failure. A series with no members is a
     *   failure rather than a silent success: it means the series is gone, and
     *   the caller asked to change something that no longer exists.
     */
    suspend fun updateSeries(
        seriesId: TaskSeriesId,
        transform: (Task) -> Task,
    ): Result<List<Task>> {
        val members = repo.getSeries(seriesId)
        if (members.isEmpty()) {
            return Result.failure(IllegalStateException("Series $seriesId has no members"))
        }
        return repo.updateAll(members.map(transform))
    }

    /**
     * Archives every member of [seriesId] and returns the ids it archived.
     *
     * Archiving, not deleting: the trash is the same trash a single task goes
     * to, so the user can undo the whole series from one place. The ids come
     * back because the undo has to put back exactly what went away — a series
     * that was rebuilt from "the members of that id" would restore a series
     * that had meanwhile changed.
     */
    suspend fun archiveSeries(seriesId: TaskSeriesId): Result<List<TaskId>> {
        val members = repo.getSeries(seriesId)
        if (members.isEmpty()) {
            return Result.failure(IllegalStateException("Series $seriesId has no members"))
        }
        val archived = mutableListOf<TaskId>()
        members.forEach { member ->
            repo.softDelete(member.id).onSuccess { archived += member.id }
        }
        return Result.success(archived)
    }
}

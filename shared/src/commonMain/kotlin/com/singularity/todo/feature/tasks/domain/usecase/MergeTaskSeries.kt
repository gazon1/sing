package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.feature.tasks.domain.logic.TaskSeriesInvariants
import com.singularity.todo.feature.tasks.domain.logic.TaskSeriesKinds
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository

/**
 * Makes two series one series.
 *
 * ## Why this exists
 *
 * Two series that are the same work happen all the time: the work was written
 * down twice, or it was written down as two things before it was noticed. The
 * person who notices it is looking at one of the rows, and the only thing they
 * can do about it today is to edit both series by hand.
 *
 * So the two become one. The series the person is looking at keeps its
 * identity — its id and the fields that describe the work — and the rows of
 * the other series join it, taking the shared fields of the one they joined.
 * The dates stay what they were: the point of a series is that one work has
 * several dates, and merging two of them gives the work more dates, not fewer.
 *
 * ## Which story wins
 *
 * The series of the row the person is on. They are looking at it, so it is the
 * one they recognise, and a merge that silently renamed the work under them
 * would be the opposite of helpful. The rows that join take its shared fields
 * — that is what makes the merged series say one thing about the work.
 */
class MergeTaskSeriesUseCase(private val repo: TaskRepository) {

    /**
     * Merges the series [otherSeriesId] into [seriesId].
     *
     * @return every member of the merged series, or a failure. Merging a series
     *   with itself is a mistake in the caller: the result would be the same
     *   series, written twice.
     */
    suspend operator fun invoke(seriesId: TaskSeriesId, otherSeriesId: TaskSeriesId): Result<List<Task>> {
        val problem = validate(seriesId, otherSeriesId)
        if (problem != null) {
            return Result.failure(problem)
        }
        val members = repo.getSeries(seriesId)
        val others = repo.getSeries(otherSeriesId)

        // The rows that join take the shared fields of the series they joined, and
        // keep everything that belongs to the occurrence they are.
        val joined = TaskSeriesInvariants.alignedTo(
            others.map { it.copy(seriesId = seriesId) },
            members.first(),
        )
        val merged = members + joined
        return repo.updateAll(merged)
    }

    /**
     * The reason [seriesId] and [otherSeriesId] may not be joined, or `null`.
     *
     * The reasons are checked before any write: a join of two series of different
     * kinds, of two recurring series with different rules, of a series with itself
     * or of a series that is not there are all mistakes in the caller, and none of
     * them may leave a write behind.
     */
    private suspend fun validate(seriesId: TaskSeriesId, otherSeriesId: TaskSeriesId): Throwable? {
        if (seriesId == otherSeriesId) {
            return IllegalArgumentException("A series is not merged with itself: $seriesId")
        }
        val members = repo.getSeries(seriesId)
        val others = repo.getSeries(otherSeriesId)
        if (members.isEmpty()) return IllegalStateException("Series $seriesId has no members")
        if (others.isEmpty()) return IllegalStateException("Series $otherSeriesId has no members")

        // A series has one kind, and a join must not mix them. Joining a recurring
        // series with a series of picked dates would leave the result holding a
        // rule and hand-picked dates; joining two recurring series with different
        // rules would leave the members disagreeing about the work (FR-050).
        return TaskSeriesKinds.joinRefusal(members, others)?.let { IllegalArgumentException(it) }
    }
}

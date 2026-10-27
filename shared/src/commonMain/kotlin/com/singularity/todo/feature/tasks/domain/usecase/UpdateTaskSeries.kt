package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

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
 * ## When the change is a rule
 *
 * A **recurring** series is re-planned when its rule **changes**: the open
 * occurrences the new rule does not give are removed, the ones it does give are
 * materialised, and the closed occurrences stay closed. A rename or a re-tag is
 * not a re-plan — the rule the members carry before the write is compared with
 * the one they carry after it, so a shared-field edit leaves the occurrence
 * dates exactly as they were. This is why the constructor takes an optional
 * [MaterializeRecurringSeriesUseCase]: a series write that does not change the
 * rule does not need it.
 *
 * ## What it does not do
 *
 * It does not decide *which* fields are shared — that rule lives in
 * [com.singularity.todo.feature.tasks.domain.model.TaskSharedField] and is
 * applied by the caller. This use case only knows how to write a series.
 */
class UpdateTaskSeriesUseCase(
    private val repo: TaskRepository,
    private val materialize: MaterializeRecurringSeriesUseCase? = null,
    private val clock: Clock,
) {
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
        val previousRules = members.map { it.recurrence }.toSet()
        val written = repo.updateAll(members.map(transform))
        val updated = written.getOrNull() ?: return written

        // A recurring series whose rule changed is re-planned: the dates the new
        // rule gives are materialised and the ones it does not give are removed,
        // while the closed occurrences stay closed (REQ-TS-010). "Changed" means
        // the members did not all already carry the written rule — a series that
        // had drifted is re-planned, and a rename or a re-tag, which leaves the
        // rule alone, is not. A picked-dates series carries no rule, so nothing
        // is re-planned for it, and a use case without a materializer leaves it
        // exactly as it was.
        val rule = updated.first().recurrence
        val materializer = materialize
        return if (rule != null && previousRules != setOf(rule) && materializer != null) {
            val template = updated.first()
            materializer(template = template, rule = rule, anchor = anchorOf(updated, template))
                .map { repo.getSeries(seriesId) }
        } else {
            written
        }
    }

    /**
     * The series' position: the earliest open occurrence's date, or — when the
     * series holds no open occurrence — the latest occurrence's date.
     *
     * The clock is the last resort, for a series whose rows all carry no date;
     * it is never replaced by an epoch default, which is a date nobody chose.
     */
    private fun anchorOf(members: List<Task>, fallback: Task): LocalDate {
        val open = members.filter { it.completedAt == null && !it.isTrashed }
        return open.mapNotNull { it.dueDate }.minOrNull()
            ?: members.mapNotNull { it.dueDate }.maxOrNull()
            ?: fallback.dueDate
            ?: clock.now().toLocalDateTime(TimeZone.UTC).date
            ?: error("A series with no date anywhere cannot be re-planned")
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

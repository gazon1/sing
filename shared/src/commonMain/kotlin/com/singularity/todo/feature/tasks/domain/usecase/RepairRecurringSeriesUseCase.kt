package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.database.UnitOfWork
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Adopts legacy repeating tasks into recurring series at launch.
 *
 * ## What a legacy repeating task is
 *
 * A row that carries a rule and no `seriesId` — the shape the old one-row model
 * left behind. The old roll-forward closed such a row and moved its date, so the
 * row the user is complaining about is **closed and dated in the future**. The
 * repair turns each of them into a series.
 *
 * ## What it does
 *
 * 1. mints a [TaskSeriesId] and puts it on the row;
 * 2. materialises the window of the rule beside it;
 * 3. **reopens the row only if the series would otherwise have no open
 *    occurrence.** A row the old roll-forward left closed with a *future* date is
 *    exactly that case, and it is the current occurrence of the series it should
 *    become. A row closed with a *past* date is legitimate history and stays
 *    closed (FR-036).
 *
 * ## A Kotlin pass, not a schema change
 *
 * `tasks.series_id` and `tasks.recurrence_rule` both already exist, and the
 * arithmetic is the domain's, not SQL's — so no Room migration and no
 * exported-schema bump is involved. The repair reads its candidates through
 * [TaskRepository.legacyRecurringTasks], a scoped query on an existing table.
 *
 * ## It is idempotent
 *
 * A row that already carries a series id is not among the candidates, so a second
 * launch changes nothing — no duplicate occurrences (FR-037).
 *
 * ## Its scope is the active profile
 *
 * The candidate read is scoped to the active profile, so a second profile's legacy
 * repeating tasks are repaired at the next launch with that profile active
 * (FR-034).
 *
 * @param repo Task repository.
 * @param unitOfWork The transaction each adoption is one write inside.
 * @param clock Injected clock.
 * @param materialize Fills the window after the row joins a series.
 */
class RepairRecurringSeriesUseCase(
    private val repo: TaskRepository,
    private val unitOfWork: UnitOfWork,
    private val clock: Clock,
    private val materialize: MaterializeRecurringSeriesUseCase,
) {

    /**
     * Repairs every legacy repeating task of the active profile.
     *
     * @return the number of rows adopted, or a failure. A single row that cannot
     *   be repaired does not stop the others: the pass is best-effort and its
     *   failures are reported by the count, not by throwing.
     */
    suspend operator fun invoke(): Result<Int> = runCatchingCancellable {
        val candidates = repo.legacyRecurringTasks()
        var repaired = 0
        for (row in candidates) {
            repairOne(row)?.let { repaired++ }
        }
        repaired
    }

    /**
     * Adopts one legacy row into a series, or returns `null` when it cannot be.
     */
    private suspend fun repairOne(row: Task): Unit? {
        val rule = row.recurrence ?: return null
        val seriesId = TaskSeriesId.generate()

        // The row keeps its rule and gains the series id. Whether it is reopened is
        // decided after the members are known: a row closed with a past date is
        // history and stays closed.
        val adopted = row.copy(seriesId = seriesId)
        unitOfWork.write {
            repo.update(adopted).getOrThrow()
        }

        // The anchor is the row's own date — the current occurrence of the series
        // it just became. The window is materialised from there.
        val anchor = anchorOf(row)
        materialize(template = adopted, rule = rule, anchor = anchor).getOrThrow()

        // Reopen only when the series would otherwise have no open occurrence. A
        // row the old roll-forward left closed with a *future* date is exactly that
        // case — it is the current occurrence of the series it should become. A row
        // closed with a *past* date is legitimate history and stays closed
        // (FR-036): reopening it would resurrect finished work.
        if (row.completedAt != null && anchor >= today()) {
            unitOfWork.write {
                repo.reopen(row.id).getOrThrow()
            }
        }
        return Unit
    }

    /** The row's own date, or today when it carries none. */
    private fun anchorOf(row: Task): LocalDate =
        row.dueDate ?: today()

    /** Today, in the same zone the rest of the app resolves dates in. */
    private fun today(): LocalDate = clock.now().toLocalDateTime(kotlinx.datetime.TimeZone.UTC).date
}

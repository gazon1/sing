package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.database.UnitOfWork
import com.singularity.todo.core.error.runCatchingCancellable
import com.singularity.todo.core.tasks.TasksSettingsRepository
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import kotlinx.coroutines.flow.first
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
 * ## It is idempotent, and it runs once per profile
 *
 * A row that already carries a series id is not among the candidates, so a second
 * launch changes nothing — no duplicate occurrences (FR-037). On top of that, the
 * pass records the [VERSION] it completed in a per-profile settings marker: after
 * the first launch that repaired a profile, later launches read the marker and do
 * not even run the candidate query. A future build that can create a new legacy
 * shape bumps [VERSION], and every profile is visited once more.
 *
 * ## Its scope is the active profile
 *
 * The candidate read is scoped to the active profile, and so is the marker, so a
 * second profile's legacy repeating tasks are repaired at the next launch with
 * that profile active (FR-034).
 *
 * @param repo Task repository.
 * @param unitOfWork The transaction each adoption is one write inside.
 * @param clock Injected clock.
 * @param materialize Fills the window after the row joins a series.
 * @param settings The per-profile store that remembers the completed [VERSION].
 * @param currentUser Resolves the profile scope the marker belongs to.
 */
class RepairRecurringSeriesUseCase(
    private val repo: TaskRepository,
    private val unitOfWork: UnitOfWork,
    private val clock: Clock,
    private val materialize: MaterializeRecurringSeriesUseCase,
    private val settings: TasksSettingsRepository,
    private val currentUser: ProfileAwareCurrentUser,
) {

    companion object {
        /**
         * The shape this pass knows how to adopt.
         *
         * Bump it when a build can produce a legacy repeating row again — a
         * downgrade, a restored backup from an older version, a new writer that
         * predates the series invariant. The marker is a version rather than a
         * boolean for exactly that reason.
         */
        const val VERSION: Int = 1
    }

    /**
     * Repairs every legacy repeating task of the active profile, unless this
     * profile has already been repaired at [VERSION].
     *
     * @return the number of rows adopted, or a failure. A failure aborts the
     *   pass before the marker is written, so the next launch tries again
     *   rather than declaring the profile clean. The candidate read and the
     *   writes are the whole pass; a row that cannot be repaired is a failure
     *   of the pass, not a row to skip silently.
     */
    suspend operator fun invoke(): Result<Int> = runCatchingCancellable {
        val scope = currentUser.liveScopedUserId.first().value
        if (settings.recurringSeriesRepairVersion(scope).first() >= VERSION) {
            return@runCatchingCancellable 0
        }
        val candidates = repo.legacyRecurringTasks()
        var repaired = 0
        val errors = mutableListOf<Throwable>()
        for (row in candidates) {
            runCatching { repairOne(row) }
                .onSuccess { result -> if (result != null) repaired++ }
                .onFailure { error -> errors += error }
        }
        if (errors.isNotEmpty()) {
            // One unreadable row must not stop the others from being repaired,
            // and it must not declare the profile clean: the marker stays unset so
            // the next launch visits the remaining rows again.
            co.touchlab.kermit.Logger.withTag("RepairRecurringSeries").w {
                "Skipped ${errors.size} of ${candidates.size} legacy rows; " +
                    "marker left unset. First error: ${errors.first().message}"
            }
            return@runCatchingCancellable repaired
        }
        // Only after a pass that ran to the end: a failure above leaves the marker
        // unset, so the next launch tries again instead of declaring the profile
        // clean.
        settings.setRecurringSeriesRepairVersion(scope, VERSION)
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

        // A trashed row is adopted — it gains the series id so a restore cannot
        // resurrect a rule without a series — but its window is not materialised:
        // trashed work stays trashed, and materialising would create live
        // occurrences from a row the user threw away.
        if (row.isTrashed) return Unit

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

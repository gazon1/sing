package com.singularity.todo.feature.projects.presentation.viewmodel

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.reminders.ReminderOffset
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.reminders.ProjectReminder
import com.singularity.todo.feature.reminders.ProjectReminderId
import com.singularity.todo.feature.reminders.domain.port.ProjectRemindersRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/** Milliseconds in a minute — converts a due-date gap into a stored fire offset. */
private const val MILLIS_PER_MINUTE = 60_000L

/**
 * Placeholder owner for a newly created project reminder.
 *
 * [ProjectRemindersRepository.upsert] asserts the caller may write this entity and
 * then re-stamps the real owner, so this value never reaches the database. It
 * exists only because the domain model is total — a reminder with no owner is not
 * a state the type system should allow, even transiently.
 */
private const val ANONYMOUS_SENTINEL = "anonymous"

/**
 * The project's own offset and the user's configured default, as one value.
 *
 * Both are consumed by the same picker and both travel into the same `copy`, so
 * they are folded rather than combined separately: the `combine` downstream is
 * already at kotlinx's five-argument limit, and a sixth argument would need a
 * vararg call that collapses to `Array<Any?>`. Mirrors [TaskVisibility].
 */
internal data class ReminderOffsets(val current: Int?, val default: ReminderOffset)

/** What [ProjectReminderCoordinator.set] did, or why it did nothing. */
internal sealed interface SetReminderOutcome {
    /** An offset was written, or re-anchored onto the new due date. */
    data object Applied : SetReminderOutcome

    /** A null offset removed the existing reminder. */
    data object Removed : SetReminderOutcome

    /** An offset was asked for on a project with no due date to anchor it to. */
    data object NoDueDate : SetReminderOutcome

    /** The repository refused the write. */
    data class Failed(val cause: Throwable) : SetReminderOutcome
}

/**
 * Everything about *when* a project's reminder fires.
 *
 * ## Why this is separate from the ViewModel
 *
 * The reminder concern used to be spread across four regions of
 * `ProjectDetailViewModel`: two flow declarations, two collector bodies, a slot in
 * the second `combine`, and a 28-line write helper. The rule worth protecting —
 * `fireAt = startOfDueDate − offset` — lived inside a collector lambda, so it was
 * only ever asserted through a ViewModel, and only indirectly.
 *
 * It is here as a named function ([offsetFromDueDate]) and a named state pair
 * ([ReminderOffsets]) instead.
 *
 * ## It holds no state
 *
 * Unlike a sub-ViewModel, this class owns nothing the screen collects. The
 * ViewModel keeps the flow slots and both `combine`s, because the screen must
 * observe one atomic `Content` rather than a composition of independent streams
 * (see `ProjectDetailViewModel`'s class KDoc). What moved is the derivation and
 * the write, not the state.
 *
 * ## The project is read as a snapshot, deliberately
 *
 * [project] is a *function*, not a flow, and that is not an oversight. The
 * original collector recomputed the offset only when a reminder row changed; it
 * read the project's due date as a snapshot at that moment. Combining it into a
 * flow would additionally recompute whenever the due date changed, which is a
 * different behaviour — and one that would make a due-date edit re-derive the
 * offset from a reminder the user has not re-picked yet. The snapshot read is
 * what keeps those two events independent.
 */
internal class ProjectReminderCoordinator(
    private val projectId: ProjectId,
    private val project: () -> Project?,
    private val reminders: ProjectRemindersRepository,
    private val defaultOffset: Flow<ReminderOffset>,
) {

    /**
     * The offset the picker shows, paired with the user's configured default.
     *
     * Seeded with the repository's stored default rather than `null` so the picker
     * always has something to preselect.
     */
    val offsets: Flow<ReminderOffsets> =
        combine(
            currentOffset(),
            defaultOffset,
            ::ReminderOffsets,
        )

    /**
     * The project's own offset in minutes, or null when none is set.
     *
     * The repository stores an absolute [ProjectReminder.fireAt]; this derives the
     * offset so the picker can show what the user actually chose.
     */
    private fun currentOffset(): Flow<Int?> =
        reminders.watchByProject(projectId).map { stored ->
            val due = project()?.dueDate ?: return@map null
            stored.firstOrNull()?.let { offsetFromDueDate(due, it) }
        }

    /**
     * Convert a stored absolute fire instant back into an offset from [due].
     *
     * Named because it is the inverse of the rule [set] writes by, and because
     * the two drifting apart is exactly the bug that would show the user a
     * different offset than the one they picked.
     */
    private fun offsetFromDueDate(due: LocalDate, reminder: ProjectReminder): Int {
        val dueMillis = due.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        return ((dueMillis - reminder.fireAt) / MILLIS_PER_MINUTE).toInt()
    }

    /**
     * Attach, re-anchor or remove the project's reminder.
     *
     * Stored as an absolute [ProjectReminder.fireAt] because that is what an alarm
     * scheduler needs, but *authored* as an offset from the due date — so editing
     * the due date re-anchors the alarm instead of leaving a reminder that fires
     * at the old instant.
     *
     * A project with no due date has nothing to anchor to, so picking an offset
     * there reports [SetReminderOutcome.NoDueDate] rather than silently dropping
     * the request.
     *
     * ## The row this writes is never fired
     *
     * There is no `ProjectReminderScheduler` on any platform, so this persists an
     * instant that nothing will ever act on. The logic is correct and tested, and
     * the UI is gated off via `PROJECT_REMINDERS_SUPPORTED` so no user can reach
     * it — the re-anchoring logic is worth keeping for the scheduler follow-up.
     * Do not call it from a new surface before that flag is flipped.
     */
    suspend fun set(offsetMinutes: Int?): SetReminderOutcome {
        val due = project()?.dueDate
        if (offsetMinutes != null && due == null) {
            return SetReminderOutcome.NoDueDate
        }

        val existing = reminders.watchByProject(projectId).first().firstOrNull()
        val result = if (offsetMinutes == null) {
            reminders.deleteByProject(projectId)
        } else {
            reminders.upsert(
                ProjectReminder(
                    // Reuse the existing id so re-picking updates in place instead of
                    // leaving one alarm row per change.
                    id = existing?.id ?: ProjectReminderId.generate(),
                    projectId = projectId,
                    // The repository asserts ownership and re-stamps the owner, so the
                    // caller does not need to know the current user id.
                    userId = existing?.userId ?: UserId(ANONYMOUS_SENTINEL),
                    fireAt = fireAtFor(due!!, offsetMinutes),
                ),
            )
        }
        return result.fold(
            onSuccess = { if (offsetMinutes == null) SetReminderOutcome.Removed else SetReminderOutcome.Applied },
            onFailure = { SetReminderOutcome.Failed(it) },
        )
    }

    /** The instant an alarm must fire for a reminder [offsetMinutes] before [due]. */
    private fun fireAtFor(due: LocalDate, offsetMinutes: Int): Long {
        val startOfDay = due.atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        return startOfDay - offsetMinutes * MILLIS_PER_MINUTE
    }
}

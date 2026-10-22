package com.singularity.todo.feature.tasks.domain.port

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.repository.SoftDeletable
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.domain.model.TagEditActor
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskSeriesId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate

/**
 * Repository for task persistence and observation.
 *
 * Extends [SoftDeletable]: soft-delete + restore are supported.
 * All observation variants automatically re-subscribe when the active user changes.
 */
interface TaskRepository : SoftDeletable<Task, TaskId> {

    // ── GenericUserScopedRepository contract ─────────────────────────────────────

    /** Returns the ambient userId for this repository's scope. */
    suspend fun currentUserId(): UserId

    fun observeAll(): Flow<List<Task>>

    fun observe(id: TaskId): Flow<Task?>

    suspend fun get(id: TaskId): Task?

    suspend fun create(item: Task): Result<Task>

    suspend fun update(item: Task): Result<Task>

    suspend fun delete(id: TaskId): Result<Unit>

    /**
     * Upserts a task from a remote sync event.
     * Does NOT emit [_changes] — caller is responsible for observability.
     * Used exclusively by pull handlers in [com.singularity.todo.core.sync.SyncBootstrapper].
     */
    suspend fun upsert(task: Task): Task

    // ── Domain-specific ─────────────────────────────────────────────────────────

    /**
     * Hard-deletes every task the active profile owns — archived and trashed
     * tasks included — and reports how many rows went.
     *
     * ## Why this exists
     *
     * A sweep that enumerates [observeAll] cannot empty a profile: `observeAll`
     * reports the live tasks only, so every archived task survives the sweep and
     * turns up in the next run's trash — the previous run's state, seen by the
     * next one. This is the one operation that reaches the rows no live read
     * sees.
     *
     * The rows are gone for good rather than archived: the archive is how a
     * *finished* task is kept, and it is exactly what makes a wiped profile
     * still show rows.
     *
     * ## What it does not do
     *
     * It touches nothing outside the active profile: the scope is the current
     * profile's scoped user id, which is what every row of that profile
     * carries. The profile and the session are left alone.
     *
     * @return the number of rows removed.
     */
    suspend fun deleteAllForCurrentProfile(): Result<Int>

    suspend fun softDelete(id: TaskId): Result<Unit>

    /**
     * Completes the task, stamping it with the repository's own clock.
     *
     * ## The refusal
     *
     * A task whose prerequisite is still open is not completed: the write is
     * refused and the answer says so. The refusal is not a check that runs
     * before the write — it *is* the write, a statement that completes the task
     * only while nothing stands in the way. A check-then-write would have a
     * window between the two, and the window is where another device reopens
     * the prerequisite.
     *
     * @param id the task to complete.
     * @param force complete even while a prerequisite is open. The caller passes
     *   `true` only after the user has confirmed.
     * @return `true` when the task was completed, `false` when the completion
     *   was refused because a prerequisite is still open.
     */
    suspend fun complete(id: TaskId, force: Boolean = false): Result<Boolean>

    /**
     * Takes a completed task back into work.
     *
     * Never gated: a task that was closed can always be opened again, and the
     * direction of the change is the user's business, not the dependency
     * graph's.
     */
    suspend fun reopen(id: TaskId): Result<Unit>

    /**
     * Closes one occurrence of a recurring task, without moving it forward.
     *
     * A recurring task is not closed in place: closing one occurrence stamps
     * the completion, and moving the row to its next due date is a second
     * write. This is the first of the two, and it is the same guarded
     * statement the ordinary close uses — the write refuses while a
     * prerequisite is open, so a roll-forward cannot land on a task whose
     * prerequisite has just come back.
     *
     * @param id the task whose occurrence is closed.
     * @param completedAt when the occurrence was completed.
     * @param updatedAt the timestamp of the write.
     * @param force close even while a prerequisite is open. The caller passes
     *   `true` only after the user has confirmed.
     * @return `true` when the occurrence was closed, `false` when the write
     *   refused it because a prerequisite is still open.
     */
    suspend fun completeOccurrence(
        id: TaskId,
        completedAt: Long,
        updatedAt: Long,
        force: Boolean = false,
    ): Result<Boolean>

    /**
     * Moves a task to its next due date, leaving everything else alone.
     *
     * The second half of a roll-forward, and deliberately narrow: a full-row
     * update would carry the completion back over the guard that has just
     * refused it.
     */
    suspend fun moveDueDate(id: TaskId, nextDue: LocalDate, updatedAt: Long): Result<Unit>

    /**
     * The ids among [taskIds] that have an open blocking prerequisite.
     *
     * A targeted read — the edges of the given tasks only, not the whole task
     * list.
     */
    suspend fun blockedTaskIds(taskIds: Collection<TaskId>): Set<TaskId>
    suspend fun togglePinned(id: TaskId): Result<Unit>
    suspend fun setTags(taskId: TaskId, tagIds: List<TagId>, actor: TagEditActor = TagEditActor.User): Result<Unit>
    fun getTagIds(taskId: TaskId): Flow<List<TagId>>

    /**
     * Replaces the full dependency set for [taskId] using [DependencyVerb.BLOCKS].
     * Uses a diff-and-apply strategy: clears all existing BLOCKS refs, inserts the new set.
     *
     * For typed dependencies use [setDependency].
     */
    suspend fun setDependencies(taskId: TaskId, deps: Set<TaskId>): Result<Unit>

    /**
     * Adds or removes a single typed dependency edge.
     *
     * @param from The owner task (the one holding the edge).
     * @param to The dependency target.
     * @param verb The semantic relationship from [from] to [to].
     * @param enabled `true` to insert the edge, `false` to remove it.
     */
    suspend fun setDependency(from: TaskId, to: TaskId, verb: DependencyVerb, enabled: Boolean): Result<Unit>

    suspend fun exists(id: TaskId): Boolean

    // ── Series ─────────────────────────────────────────────────────────────────

    /**
     * Creates every row of a series in one transaction.
     *
     * One transaction is the point: a series that landed halfway would be a set of
     * rows that share an id but describe different work, and no reader could tell.
     * A failure therefore leaves nothing behind.
     */
    suspend fun createSeries(tasks: List<Task>): Result<List<Task>>

    /**
     * Writes every row of a series in one transaction, with the same
     * read-before-write guard the single-row [update] has.
     */
    suspend fun updateAll(tasks: List<Task>): Result<List<Task>>

    /** Observes the rows of [seriesId] for the currently active user, in date order. */
    fun observeSeries(seriesId: TaskSeriesId): Flow<List<Task>>

    /** The rows of [seriesId] as of now — the one-shot form of [observeSeries]. */
    suspend fun getSeries(seriesId: TaskSeriesId): List<Task> = observeSeries(seriesId).first()

    // ── User-scoped observers ──────────────────────────────────────────────────

    /**
     * Observes tasks matching [filter] for the currently active user.
     * Re-subscribes automatically when the user changes.
     */
    fun observeByFilter(filter: TaskFilter): Flow<List<Task>>

    /**
     * Returns tasks scheduled for exactly [date], ordered by pin status then due time.
     * Re-subscribes automatically when the user changes.
     */
    fun observeByDate(date: LocalDate): Flow<List<Task>>

    /**
     * Returns direct child tasks of the given [parentId].
     * Re-subscribes automatically when the user changes.
     */
    fun observeSubtasks(parentId: TaskId): Flow<List<Task>>
}

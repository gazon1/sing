package com.singularity.todo.feature.tasks.domain.port

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.repository.SoftDeletable
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tags.domain.model.TagEditActor
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDependency
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.flow.Flow
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

    suspend fun softDelete(id: TaskId): Result<Unit>
    suspend fun toggleComplete(id: TaskId): Result<Unit>
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

    /**
     * Returns the set of task IDs that [taskId] depends on (is blocked by).
     * The forward direction of the dependency graph. Drops verb information.
     */
    fun observeDependencies(taskId: TaskId): Flow<Set<TaskId>>

    /**
     * Returns typed dependency edges for [taskId].
     */
    fun observeTypedDependencies(taskId: TaskId): Flow<List<TaskDependency>>

    /**
     * Returns the set of task IDs that are blocked by [taskId] — i.e., the tasks
     * that depend on [taskId]. The reverse direction of the dependency graph.
     */
    fun observeBlockedBy(taskId: TaskId): Flow<Set<TaskId>>
}

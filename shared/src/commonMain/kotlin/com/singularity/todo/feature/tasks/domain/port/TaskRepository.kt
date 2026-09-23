package com.singularity.todo.feature.tasks.domain.port

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.repository.SoftDeletable
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.datetime.LocalDate

/**
 * Repository for task persistence and observation.
 *
 * Extends [SoftDeletable]: soft-delete + restore are supported.
 * All observation variants automatically re-subscribe when the active user changes.
 */
interface TaskRepository : SoftDeletable<Task, TaskId> {

    /** Emits every task after it's created or updated — for SyncEngine observer */
    val changes: SharedFlow<Task>

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
    suspend fun setTags(taskId: TaskId, tagIds: List<TagId>): Result<Unit>
    fun getTagIds(taskId: TaskId): Flow<List<TagId>>

    /**
     * Replaces the full dependency set for [taskId].
     * Uses a diff-and-apply strategy: clears all existing refs, inserts the new set.
     */
    suspend fun setDependencies(taskId: TaskId, deps: Set<TaskId>): Result<Unit>

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
     * The forward direction of the dependency graph.
     */
    fun observeDependencies(taskId: TaskId): Flow<Set<TaskId>>

    /**
     * Returns the set of task IDs that depend on [taskId] (it blocks them).
     * The reverse direction of the dependency graph.
     */
    fun observeBlockingBy(taskId: TaskId): Flow<Set<TaskId>>
}

package com.singularity.todo.feature.tasks.domain.port

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.repository.UserScopedRepository
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
 * Implements [UserScopedRepository]: all [observeAllForCurrentUser] and [observeByFilter]
 * variants automatically re-subscribe when the active user changes.
 *
 * ## Observation naming convention
 * - Methods with `ForCurrentUser` suffix: base interface contract
 *   ([observeAllForCurrentUser], [observeForCurrentUser]).
 * - Domain-specific observers: short form without suffix
 *   ([observeByFilter], [observeByDate], [observeSubtasks], etc.).
 *
 * ## Auth-safety (caller-trust)
 * The repository does **not** overwrite `entity.userId` on create/update.
 * Callers are responsible for providing correct `userId` (typically via
 * [com.singularity.todo.feature.profile.ProfileAwareCurrentUser.current]).
 */
interface TaskRepository : UserScopedRepository<Task, TaskId> {
    /** Emits every task after it's created or updated — for SyncEngine observer */
    val changes: SharedFlow<Task>

    suspend fun softDelete(id: TaskId): Result<Unit>
    suspend fun restore(id: TaskId): Result<Unit>
    suspend fun toggleComplete(id: TaskId): Result<Unit>
    suspend fun togglePinned(id: TaskId): Result<Unit>
    suspend fun setTags(taskId: TaskId, tagIds: List<TagId>): Result<Unit>
    fun getTagIds(taskId: TaskId): Flow<List<TagId>>

    /**
     * Returns the set of task IDs that [taskId] depends on (is blocked by).
     */
    fun watchDependencies(taskId: TaskId): Flow<Set<TaskId>>

    /**
     * Returns the set of task IDs that depend on [taskId] (it blocks them).
     * The reverse direction of `watchDependencies`.
     */
    fun watchBlockingBy(taskId: TaskId): Flow<Set<TaskId>>

    /**
     * Replaces the full dependency set for [taskId].
     * Uses a diff-and-apply strategy: clears all existing refs, inserts the new set.
     */
    suspend fun setDependencies(taskId: TaskId, deps: Set<TaskId>): Result<Unit>

    suspend fun exists(id: TaskId): Boolean
    suspend fun getById(id: TaskId): Task?

    // ── User-scoped observers (new API — use these in VMs) ──────────────────────

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

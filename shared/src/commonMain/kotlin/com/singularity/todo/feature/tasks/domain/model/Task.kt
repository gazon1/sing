package com.singularity.todo.feature.tasks.domain.model

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.core.sync.DocType
import com.singularity.todo.core.sync.Hlc
import com.singularity.todo.core.sync.SyncableEntity
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import kotlin.time.Instant

@Serializable
@JvmInline
value class TaskId(val value: String) {
    companion object {
        fun generate() = TaskId(com.singularity.todo.core.ids.nextId())
        fun fromString(value: String) = TaskId(value)
    }
}

enum class TaskPriority {
    None,
    Low,
    Medium,
    High,
    Urgent,
}

enum class TaskKind {
    Task,
    Note,
}

sealed interface TaskFilter {
    data object Today : TaskFilter
    data object Upcoming : TaskFilter
    data object Someday : TaskFilter
    data object Inbox : TaskFilter
    data object Pinned : TaskFilter
    data object Trash : TaskFilter
    data object All : TaskFilter
    data class ByProject(val id: ProjectId) : TaskFilter
    data class ByTag(val id: TagId) : TaskFilter
    data class Search(val query: String) : TaskFilter

    /** Calendar screen: all tasks with dueDate within [from]..[to] (inclusive). */
    data class ByDateRange(val from: kotlinx.datetime.LocalDate, val to: kotlinx.datetime.LocalDate) : TaskFilter

    /** Filters by completion status set (Active / Completed / or both = All). */
    data class ByStatuses(val statuses: Set<TaskStatus>) : TaskFilter

    /**
     * Filters tasks that are tagged with any/all of the given [TagId]s.
     *
     * - [matchAll] = false (default): task must have **at least one** tag from [ids]
     * - [matchAll] = true: task must have **all** tags from [ids]
     *
     * The SQL dispatch in [com.singularity.todo.feature.tasks.data.TaskRepositoryImpl]
     * uses `watchByAnyTag` / `watchByAllTags` respectively.
     *
     * @param matchAll When true, tasks must be tagged with every [TagId] in [ids].
     *                 When false (default), tasks tagged with any one of [ids] are included.
     */
    data class ByTags(val ids: Set<TagId>, val matchAll: Boolean = false) : TaskFilter

    /**
     * Filters tasks by priority set.
     * A task matches if its priority is **contained in** [priorities].
     */
    data class ByPriorities(val priorities: Set<TaskPriority>) : TaskFilter

    /**
     * Filters tasks by title substring match (case-insensitive).
     * Uses SQL `lower(title) LIKE lower('%' || :query || '%')` — no regular expression.
     *
     * Equivalent in-memory predicate for [Selector.Regexp] uses `Regex`.
     * The SQL variant avoids loading all tasks into memory for pre-filtering.
     */
    data class ByRegexp(val pattern: String) : TaskFilter

    /**
     * Filters tasks by a relative date bucket evaluated against [today].
     *
     * The [today] parameter is required because SQL query dispatch is asynchronous —
     * the bucket must be resolved to a concrete date range at dispatch time.
     *
     * @param bucket The relative bucket (Today, ThisWeek, Overdue, etc.).
     * @param today The reference "today" used to resolve relative ranges.
     */
    data class ByDateBucket(val bucket: RelativeBucket, val today: kotlinx.datetime.LocalDate) : TaskFilter
}

data class Task(
    val id: TaskId,
    val title: String,
    val description: String? = null,
    val priority: TaskPriority = TaskPriority.None,
    val kind: TaskKind = TaskKind.Task,
    val projectId: ProjectId? = null,
    val parentTaskId: TaskId? = null,
    val tags: List<TagId> = emptyList(),
    val dueDate: kotlinx.datetime.LocalDate? = null,
    val dueTime: kotlinx.datetime.LocalTime? = null, // "HH:mm:ss" via LocalTimeConverters / LocalTimeSerializer
    val completedAt: Instant? = null,
    val someday: Boolean = false,
    val archivedAt: Instant? = null,
    val isPinned: Boolean = false,
    /**
     * IDs of tasks that must be completed before this task can be completed.
     * Empty set means the task is not blocked by any dependency.
     *
     * ## Null semantics
     *
     * Stored as a join table (`task_dependencies`), never as a nullable column.
     * When loading from DB, a task with no rows in `task_dependencies` has an empty set.
     *
     * ## Blocking
     *
     * A task is **blocked** when any of its dependencies is not yet completed.
     * Computed via [com.singularity.todo.feature.tasks.domain.logic.TaskComputed.isBlocked]
     * using the full task list in scope (VM `combine`).
     *
     * @see 2026-09-18-task-dependencies
     */
    val dependsOn: Set<TaskId> = emptySet(),
    val createdAt: Instant,
    val updatedAt: Instant,
    val userId: UserId,
    // ─── Sync fields ───────────────────────────────────────────────────────────
    /** Server version from last sync; 0 = not yet synced. Stored in DB but not part of the constructor — loaded via mapper. */
    val serverVersion: Long = 0,
    /** Hybrid Logical Clock timestamp; null for local-only tasks. */
    val hlc: Hlc? = null,
) : SyncableEntity {
    val isCompleted: Boolean get() = completedAt != null
    val isTrashed: Boolean get() = archivedAt != null

    // SyncableEntity implementation
    override val syncId: String get() = id.value
    override val docType: DocType get() = DocType.Task
    override val syncServerVersion: Long get() = serverVersion
    override val syncHlc: Hlc? get() = hlc

    override fun toJson(): JsonObject {
        val ser = serializer<Task>()
        return StableJson
            .encodeToString(ser, this)
            .let { StableJson.decodeFromString<JsonObject>(it) }
    }
}

data class CreateTaskInput(
    val title: String,
    val description: String? = null,
    val priority: TaskPriority = TaskPriority.None,
    val kind: TaskKind = TaskKind.Task,
    val projectId: ProjectId? = null,
    val parentTaskId: TaskId? = null,
    val tagIds: List<TagId> = emptyList(),
    val dueDate: kotlinx.datetime.LocalDate? = null,
    val dueTime: kotlinx.datetime.LocalTime? = null,
    val someday: Boolean = false,
)

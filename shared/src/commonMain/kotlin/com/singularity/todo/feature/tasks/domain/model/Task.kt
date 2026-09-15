package com.singularity.todo.feature.tasks.domain.model

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tags.TagId
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class TaskId(val value: String) {
    companion object {
        fun generate() = TaskId(com.singularity.todo.core.ids.nextId())
        fun fromString(value: String) = TaskId(value)
    }
}

enum class TaskPriority {
    None, Low, Medium, High, Urgent
}

enum class TaskKind {
    Task, Note
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
    val createdAt: Instant,
    val updatedAt: Instant,
    val userId: UserId
) {
    val isCompleted: Boolean get() = completedAt != null
    val isTrashed: Boolean get() = archivedAt != null
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
    val userId: UserId,
)

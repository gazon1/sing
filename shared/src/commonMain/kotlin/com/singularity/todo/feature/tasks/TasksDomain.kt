package com.singularity.todo.feature.tasks

import com.singularity.todo.core.error.AppError
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tags.TagId
import kotlin.time.Instant

/**
 * Pure domain logic for task validation and creation.
 * No dependencies - fully testable without mocks.
 */
object TasksDomain {

    /**
     * Validates task title.
     * @throws AppError.Validation if title is blank
     */
    fun validateTitle(title: String) {
        require(title.isNotBlank()) { throw AppError.Validation("Title cannot be blank") }
    }

    /**
     * Creates a new TaskId.
     */
    fun generateTaskId(): TaskId = TaskId.generate()

    /**
     * Creates a CreateTaskInput with validation.
     * @throws AppError.Validation if title is blank
     */
    fun createInput(
        title: String,
        description: String? = null,
        priority: TaskPriority = TaskPriority.None,
        kind: TaskKind = TaskKind.Task,
        projectId: ProjectId? = null,
        tagIds: List<TagId> = emptyList(),
        dueDate: kotlinx.datetime.LocalDate? = null,
        dueTime: String? = null,
        someday: Boolean = false,
        userId: UserId
    ): CreateTaskInput {
        validateTitle(title)
        return CreateTaskInput(
            title = title.trim(),
            description = description?.trim(),
            priority = priority,
            kind = kind,
            projectId = projectId,
            tagIds = tagIds,
            dueDate = dueDate,
            dueTime = dueTime,
            someday = someday,
            userId = userId
        )
    }

    /**
     * Builds a Task from validated input.
     * Pure function - no side effects.
     */
    fun buildTask(
        input: CreateTaskInput,
        id: TaskId = generateTaskId(),
        createdAt: Instant,
        updatedAt: Instant
    ): Task = Task(
        id = id,
        title = input.title,
        description = input.description,
        priority = input.priority,
        kind = input.kind,
        projectId = input.projectId,
        tags = input.tagIds,
        dueDate = input.dueDate,
        dueTime = input.dueTime,
        someday = input.someday,
        createdAt = createdAt,
        updatedAt = updatedAt,
        userId = input.userId
    )

    /**
     * Checks if a task matches the given filter.
     * Pure predicate function - fully testable.
     */
    fun matchesFilter(task: Task, filter: TaskFilter, today: kotlinx.datetime.LocalDate): Boolean {
        return when (filter) {
            is TaskFilter.Today -> task.dueDate == today && !task.isTrashed && !task.someday
            is TaskFilter.Upcoming -> task.dueDate != null && task.dueDate > today && !task.isTrashed && !task.someday
            is TaskFilter.Someday -> task.someday && !task.isTrashed
            is TaskFilter.Inbox -> !task.someday && !task.isTrashed
            is TaskFilter.Trash -> task.isTrashed
            is TaskFilter.All -> !task.isTrashed
            is TaskFilter.ByProject -> task.projectId == filter.id && !task.isTrashed
            is TaskFilter.ByTag -> task.tags.contains(filter.id) && !task.isTrashed
            is TaskFilter.Search -> task.title.contains(filter.query, ignoreCase = true) ||
                    task.description?.contains(filter.query, ignoreCase = true) == true
        }
    }
}

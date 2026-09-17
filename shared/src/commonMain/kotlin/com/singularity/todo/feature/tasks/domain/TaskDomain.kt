package com.singularity.todo.feature.tasks.domain

import com.singularity.todo.core.error.AppError
import com.singularity.todo.core.error.Either
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.CreateTaskInput
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.logic.TaskComputed
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskStatus
import com.singularity.todo.feature.agenda.domain.logic.toDateRange
import com.singularity.todo.feature.agenda.domain.model.RelativeBucket
import kotlin.time.Instant

/**
 * Pure domain logic for task validation and creation.
 * No dependencies - fully testable without mocks.
 */
object TaskDomain {

    /**
     * Validates task title.
     * @return [Either.Right] with trimmed title on success, [Either.Left] with [AppError.Validation] on failure.
     */
    fun validateTitle(title: String): Either<AppError.Validation, String> =
        if (title.isNotBlank()) {
            Either.Right(title.trim())
        } else {
            Either.Left(AppError.Validation("Title cannot be blank"))
        }

    /**
     * Creates a new TaskId.
     */
    fun generateTaskId(): TaskId = TaskId.generate()

    /**
     * Creates a CreateTaskInput with validation.
     * @return [Either.Right] with input on success, [Either.Left] with [AppError.Validation] on failure.
     */
    fun createInput(
        title: String,
        description: String? = null,
        priority: TaskPriority = TaskPriority.None,
        kind: TaskKind = TaskKind.Task,
        projectId: ProjectId? = null,
        parentTaskId: TaskId? = null,
        tagIds: List<TagId> = emptyList(),
        dueDate: kotlinx.datetime.LocalDate? = null,
        dueTime: kotlinx.datetime.LocalTime? = null,
        someday: Boolean = false,
        userId: UserId,
    ): Either<AppError.Validation, CreateTaskInput> {
        val trimmed: String = when (val v = validateTitle(title)) {
            is Either.Left -> return v
            is Either.Right -> v.value
        }
        return Either.Right(
            CreateTaskInput(
                title = trimmed,
                description = description?.trim(),
                priority = priority,
                kind = kind,
                projectId = projectId,
                parentTaskId = parentTaskId,
                tagIds = tagIds,
                dueDate = dueDate,
                dueTime = dueTime,
                someday = someday,
                userId = userId,
            ),
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
        updatedAt: Instant,
    ): Task = Task(
        id = id,
        title = input.title,
        description = input.description,
        priority = input.priority,
        kind = input.kind,
        projectId = input.projectId,
        parentTaskId = input.parentTaskId,
        tags = input.tagIds,
        dueDate = input.dueDate,
        dueTime = input.dueTime,
        someday = input.someday,
        createdAt = createdAt,
        updatedAt = updatedAt,
        userId = input.userId,
    )

    /**
     * Checks if a task matches the given filter.
     * Pure predicate function - fully testable.
     */
    fun matchesFilter(task: Task, filter: TaskFilter, today: kotlinx.datetime.LocalDate): Boolean = when (filter) {
        is TaskFilter.Today -> task.dueDate == today && !task.isTrashed && !task.someday

        is TaskFilter.Upcoming -> task.dueDate != null && task.dueDate > today && !task.isTrashed && !task.someday

        is TaskFilter.Someday -> task.someday && !task.isTrashed

        is TaskFilter.Inbox -> !task.someday && !task.isTrashed

        is TaskFilter.Pinned -> task.isPinned && !task.isTrashed

        is TaskFilter.Trash -> task.isTrashed

        is TaskFilter.All -> !task.isTrashed

        is TaskFilter.ByProject -> task.projectId == filter.id && !task.isTrashed

        is TaskFilter.ByTag -> task.tags.contains(filter.id) && !task.isTrashed

        is TaskFilter.Search -> task.title.contains(filter.query, ignoreCase = true) ||
            task.description?.contains(filter.query, ignoreCase = true) == true

        is TaskFilter.ByDateRange ->
            task.dueDate != null &&
            task.dueDate >= filter.from && task.dueDate <= filter.to && !task.isTrashed

        is TaskFilter.ByStatuses -> {
            val trashed = task.isTrashed
            !trashed && when {
                TaskStatus.All in filter.statuses -> true
                TaskStatus.Active in filter.statuses && !task.isCompleted -> true
                TaskStatus.Completed in filter.statuses && task.isCompleted -> true
                else -> false
            }
        }

        is TaskFilter.ByTags -> {
            if (filter.ids.isEmpty()) {
                // Empty tag set: matchAll=false → nothing matches; matchAll=true → trivially true
                filter.matchAll && true
            } else if (filter.matchAll) {
                filter.ids.all { id -> task.tags.contains(id) }
            } else {
                filter.ids.any { id -> task.tags.contains(id) }
            } && !task.isTrashed
        }

        is TaskFilter.ByPriorities -> {
            filter.priorities.contains(task.priority) && !task.isTrashed
        }

        is TaskFilter.ByRegexp -> {
            val pattern = filter.pattern
            if (pattern.isEmpty()) {
                true && !task.isTrashed
            } else {
                task.title.contains(pattern, ignoreCase = true) && !task.isTrashed
            }
        }

        is TaskFilter.ByDateBucket -> {
            val range = filter.bucket.toDateRange(filter.today)
            when (filter.bucket) {
                RelativeBucket.Overdue ->
                    TaskComputed.isOverdue(task, filter.today)

                RelativeBucket.NoDate ->
                    task.dueDate == null && !task.isTrashed

                else ->
                    task.dueDate != null && task.dueDate >= range.from && task.dueDate <= range.to && !task.isTrashed
            }
        }
    }

    /**
     * Validates that setting [childId] as a child of [parentId] would not violate
     * the 1-level hierarchy rule: a child task cannot itself have children.
     */
    fun assertNoNesting(parentId: TaskId, childId: TaskId, allTasks: List<Task>): Either<AppError.Validation, Unit> {
        val child = allTasks.find { it.id == childId } ?: return Either.Right(Unit)
        if (child.parentTaskId != null) {
            return Either.Left(
                AppError.Validation("Sub-tasks can only be 1 level deep. \"${child.title}\" is already a sub-task."),
            )
        }
        return Either.Right(Unit)
    }
}

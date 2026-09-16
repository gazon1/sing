package com.singularity.todo.test.fakes

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

// ─── Task fixtures ─────────────────────────────────────────────────────────────

/**
 * Creates a [Task] with predictable defaults for tests.
 * Override any field via [overrides].
 *
 * Example:
 * ```
 * val task = testTask(id = TaskId.fromString("t1"))
 * val pastTask = testTask(createdAt = Instant.fromEpochMilliseconds(1000))
 * val pinnedTask = testTask(isPinned = true)
 * ```
 */
fun testTask(
    id: TaskId = TaskId.generate(),
    title: String = "Test task",
    description: String? = null,
    priority: TaskPriority = TaskPriority.None,
    kind: TaskKind = TaskKind.Task,
    projectId: ProjectId? = null,
    tags: List<TagId> = emptyList(),
    dueDate: LocalDate? = null,
    dueTime: LocalTime? = null,
    completedAt: Instant? = null,
    someday: Boolean = false,
    archivedAt: Instant? = null,
    isPinned: Boolean = false,
    createdAt: Instant = Instant.fromEpochMilliseconds(0),
    updatedAt: Instant = Instant.fromEpochMilliseconds(0),
    userId: UserId = UserId.anonymous,
    overrides: Task.() -> Unit = {},
): Task = Task(
    id = id,
    title = title,
    description = description,
    priority = priority,
    kind = kind,
    projectId = projectId,
    tags = tags,
    dueDate = dueDate,
    dueTime = dueTime,
    completedAt = completedAt,
    someday = someday,
    archivedAt = archivedAt,
    isPinned = isPinned,
    createdAt = createdAt,
    updatedAt = updatedAt,
    userId = userId,
).apply { overrides() }

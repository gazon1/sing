package com.singularity.todo.test.fakes

import com.singularity.todo.feature.tasks.Task
import com.singularity.todo.feature.tasks.TaskId
import com.singularity.todo.feature.tasks.TaskKind
import com.singularity.todo.feature.tasks.TaskPriority
import com.singularity.todo.feature.tasks.UserId
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tags.TagId
import kotlinx.datetime.LocalDate
import kotlin.random.Random
import kotlin.time.Instant

/**
 * Controllable clock for tests — wraps the real [Clock] singleton and
 * lets tests advance time deterministically.
 *
 * Usage:
 * ```
 * val clock = FakeClock(Instant.fromEpochMilliseconds(0))
 * clock.now() // returns epoch 0
 * clock.advance(60_000) // advance by 1 minute
 * clock.now() // returns epoch 60_000
 * ```
 */
class FakeClock(
    private var _now: Instant = Instant.fromEpochMilliseconds(0)
) {
    fun now(): Instant = _now

    fun advance(milliseconds: Long) {
        _now = Instant.fromEpochMilliseconds(_now.toEpochMilliseconds() + milliseconds)
    }

    fun set(milliseconds: Long) {
        _now = Instant.fromEpochMilliseconds(milliseconds)
    }
}

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
    dueTime: String? = null,
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

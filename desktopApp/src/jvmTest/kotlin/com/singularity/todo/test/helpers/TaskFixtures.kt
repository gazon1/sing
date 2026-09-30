package com.singularity.todo.test.helpers

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.test.fakes.testTask
import kotlinx.coroutines.flow.first
import org.koin.core.Koin
import kotlin.time.Instant

/**
 * Writes a task straight into the repository the running app is already bound to.
 *
 * Seeding through the repository rather than the UI keeps a flow's precondition
 * ("a task titled 'Buy milk' exists") independent of the editor's save path. That
 * matters here because the editor is its own flow: when a create-flow assertion
 * fails, the question should be "did the editor save?", not "is the whole task
 * subsystem broken?".
 *
 * The write is visible immediately — the repository's flow is driven by the same
 * `MutableStateFlow` the fake DAO writes to, so the agenda re-emits without any
 * polling.
 */
suspend fun Koin.seedTask(
    id: String = "seeded-task",
    title: String = "Buy milk",
    dueDate: kotlinx.datetime.LocalDate? = null,
    completed: Boolean = false,
    archived: Boolean = false,
    pinned: Boolean = false,
): TaskId {
    val taskId = TaskId(id)
    val repository = get<TaskRepository>()
    val userId = get<com.singularity.todo.feature.profile.ProfileAwareCurrentUser>()
        .scopedUserId.value

    val task = testTask(
        id = taskId,
        title = title,
        dueDate = dueDate,
        completedAt = if (completed) Instant.fromEpochMilliseconds(1_700_000_000_000) else null,
        archivedAt = if (archived) Instant.fromEpochMilliseconds(1_700_000_000_000) else null,
        isPinned = pinned,
        userId = userId,
        createdAt = kotlin.time.Clock.System.now(),
        updatedAt = kotlin.time.Clock.System.now(),
    )
    repository.upsert(task)
    return taskId
}

/** Convenience for the common "an undated task titled X" precondition. */
suspend fun Koin.seedBuyMilk(): TaskId = seedTask(id = "seeded-buy-milk", title = "Buy milk")

/** Blocks until [TaskRepository.observeAll] reports a task with [title]. */
suspend fun TaskRepository.awaitTask(title: String) {
    observeAll().first { list -> list.any { it.title == title } }
}

package com.singularity.todo.test.fakes

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FakeTaskRepositoryTest {

    private val userId = UserId("test-user")
    private val testTask = Task(
        id = TaskId("task-1"),
        userId = userId,
        title = "Test Task",
        priority = TaskPriority.None,
        kind = TaskKind.Task,
        createdAt = Clock.now(),
        updatedAt = Clock.now(),
    )

    @Test
    fun `toggleCompleteOverride returns injected failure`() = runTest {
        val repo = FakeTaskRepository()
        repo.add(testTask)
        repo.toggleCompleteOverride = Result.failure(IllegalStateException("injected"))

        val result = repo.toggleComplete(TaskId("task-1"))

        assertIs<IllegalStateException>(result.exceptionOrNull())
        assertEquals("injected", result.exceptionOrNull()?.message)
    }

    @Test
    fun `override cleared falls through to runCatching success`() = runTest {
        val repo = FakeTaskRepository()
        repo.add(testTask)

        repo.toggleCompleteOverride = Result.failure(IllegalStateException("injected"))
        repo.toggleCompleteOverride = null  // clear

        val result = repo.toggleComplete(TaskId("task-1"))

        assertTrue(result.isSuccess)
    }
}

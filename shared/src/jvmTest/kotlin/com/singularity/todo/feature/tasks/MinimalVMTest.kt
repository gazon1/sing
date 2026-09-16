package com.singularity.todo.feature.tasks

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MinimalVMTest {
    @Test
    fun `UpdateTaskUseCase updates repository`() = runTest {
        val repo = FakeTaskRepository()
        val task = com.singularity.todo.feature.tasks.domain.model.Task(
            id = TaskId("t1"),
            title = "Original",
            userId = UserId("u1"),
            createdAt = Clock.now(),
            updatedAt = Clock.now(),
        )
        repo.seed(task)

        val useCase = UpdateTaskUseCase(repo, Clock)
        useCase(task.copy(title = "Updated"))
        advanceUntilIdle()

        assertEquals("Updated", repo.tasks.value["t1"]?.title)
    }
}

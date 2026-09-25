package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.InMemoryTaskDao
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class DependencyValidatorImplTest {

    private val dao = InMemoryTaskDao()
    private val currentUser = FakeProfileAwareCurrentUser()
    private val validator = DependencyValidatorImpl(dao, currentUser)

    @Test
    fun `self-loop returns error`() = runTest {
        val taskId = TaskId.fromString("task-1")
        val result = validator.assertNoCycles(taskId, setOf(taskId))
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is com.singularity.todo.core.graph.CycleError.SelfLoop)
    }

    @Test
    fun `different tasks pass`() = runTest {
        val taskId = TaskId.fromString("task-1")
        val dep1 = TaskId.fromString("dep-1")
        val dep2 = TaskId.fromString("dep-2")
        val result = validator.assertNoCycles(taskId, setOf(dep1, dep2))
        assertTrue(result.isSuccess)
    }

    @Test
    fun `empty dependency set passes`() = runTest {
        val taskId = TaskId.fromString("task-1")
        val result = validator.assertNoCycles(taskId, emptySet())
        assertTrue(result.isSuccess)
    }
}

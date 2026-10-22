package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Covers the bulk close: a selection that contains a blocked task closes
 * nothing.
 */
@Tag("fast")
class TaskMutationsUseCaseTest {

    private val now: Instant = Instant.parse("2026-01-15T12:00:00Z")
    private val userId = UserId("test-user")

    private val tasks = FakeTaskRepository()
    private val mutations = TaskMutationsUseCase(tasks)

    private fun task(id: String, blockedBy: Set<TaskId> = emptySet()) = Task(
        id = TaskId(id),
        title = "Task $id",
        blockedBy = blockedBy,
        createdAt = now,
        updatedAt = now,
        userId = userId,
    )

    @Test
    fun `a selection without a blocked task is closed`() = runTest {
        tasks.seed(task("t1"), task("t2"))

        mutations.bulkComplete(listOf(TaskId("t1"), TaskId("t2"))).getOrThrow()

        assertNotNull(tasks.tasks.value["t1"]?.completedAt)
        assertNotNull(tasks.tasks.value["t2"]?.completedAt)
    }

    @Test
    fun `a blocked task in the selection stops the whole close`() = runTest {
        tasks.seed(task("dep"), task("t1"), task("t2").copy(blockedBy = setOf(TaskId("dep"))))

        val result = mutations.bulkComplete(listOf(TaskId("t1"), TaskId("t2")))

        assertTrue(result.isFailure, "the close must be refused")
        val failure = result.exceptionOrNull()
        assertTrue(
            failure is BlockedTasksException,
            "the refusal must name the tasks in the way, not fail silently: $failure",
        )
        assertEquals(setOf(TaskId("t2")), (failure as BlockedTasksException).blockedTaskIds)
    }

    @Test
    fun `nothing is written when the selection contains a blocked task`() = runTest {
        tasks.seed(task("dep"), task("t1"), task("t2").copy(blockedBy = setOf(TaskId("dep"))))

        mutations.bulkComplete(listOf(TaskId("t1"), TaskId("t2")))

        assertNull(tasks.tasks.value["t1"]?.completedAt, "not even the task that could be closed")
        assertNull(tasks.tasks.value["t2"]?.completedAt)
    }

    @Test
    fun `a task that does not exist fails the whole close`() = runTest {
        tasks.seed(task("t1"))

        val result = mutations.bulkComplete(listOf(TaskId("t1"), TaskId("nope")))

        assertTrue(result.isFailure)
        assertNull(tasks.tasks.value["t1"]?.completedAt, "nothing may be written")
    }
}

package com.singularity.todo.feature.tasks.usecase

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.usecase.TaskMutationsUseCase
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.testTask
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskMutationsUseCaseTest {

    /**
     * The fake's default current user. Tasks must be seeded as belonging to it:
     * `testTask` defaults to [UserId.anonymous], which no real user-scoped
     * repository would ever own, so the repository's ownership check rejects it.
     */
    private val user = UserId("test-user")
    private val repo = FakeTaskRepository()
    private val mutations = TaskMutationsUseCase(repo)

    private val t1 = testTask(id = TaskId.fromString("t1"), title = "Task 1", userId = user)
    private val t2 = testTask(id = TaskId.fromString("t2"), title = "Task 2", userId = user)

    // ─── bulkComplete ─────────────────────────────────────────────────────────

    @Test
    fun `bulkComplete succeeds when all ids exist`() = runTest {
        repo.seed(t1, t2)

        val result = mutations.bulkComplete(listOf(t1.id, t2.id))

        assertTrue(result.isSuccess)
        // Both tasks should now be completed
        assertTrue(repo.tasks.value[t1.id.value]!!.isCompleted)
        assertTrue(repo.tasks.value[t2.id.value]!!.isCompleted)
    }

    @Test
    fun `bulkComplete fails when any id does not exist`() = runTest {
        repo.seed(t1)

        val result = mutations.bulkComplete(listOf(t1.id, TaskId.fromString("nonexistent")))

        assertTrue(result.isFailure)
        assertEquals("Task ${TaskId.fromString("nonexistent")} not found", result.exceptionOrNull()?.message)
    }

    @Test
    fun `bulkComplete is atomic - no side effects when one id is missing`() = runTest {
        repo.seed(t1)

        mutations.bulkComplete(listOf(t1.id, TaskId.fromString("nonexistent")))

        // t1 should NOT have been completed (atomicity)
        assertTrue(!repo.tasks.value[t1.id.value]!!.isCompleted)
    }

    // ─── bulkDelete ─────────────────────────────────────────────────────────

    @Test
    fun `bulkDelete succeeds when all ids exist`() = runTest {
        repo.seed(t1, t2)

        val result = mutations.bulkDelete(listOf(t1.id, t2.id))

        assertTrue(result.isSuccess)
        // Both tasks should now be trashed
        assertTrue(repo.tasks.value[t1.id.value]!!.isTrashed)
        assertTrue(repo.tasks.value[t2.id.value]!!.isTrashed)
    }

    @Test
    fun `bulkDelete fails when any id does not exist`() = runTest {
        repo.seed(t1)

        val result = mutations.bulkDelete(listOf(t1.id, TaskId.fromString("nonexistent")))

        assertTrue(result.isFailure)
    }

    @Test
    fun `bulkDelete is atomic - no side effects when one id is missing`() = runTest {
        repo.seed(t1)

        mutations.bulkDelete(listOf(t1.id, TaskId.fromString("nonexistent")))

        // t1 should NOT have been trashed
        assertTrue(!repo.tasks.value[t1.id.value]!!.isTrashed)
    }
}

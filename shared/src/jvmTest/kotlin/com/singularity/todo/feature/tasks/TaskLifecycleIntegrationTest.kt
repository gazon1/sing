@file:Suppress("NoDirectClockSystem")

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.tasks

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Integration tests for the task lifecycle using fake repositories.
 * Each test gets its own repository instance via factory methods.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskLifecycleIntegrationTest {

    private val testUserId = UserId("test-user")

    private fun makeTaskRepo() = FakeTaskRepository()
    private fun makeChecklistRepo() = FakeChecklistRepository()

    private fun seed(
        repo: FakeTaskRepository,
        id: String,
        title: String,
        completedAt: Instant? = null,
        archivedAt: Instant? = null,
        isPinned: Boolean = false,
    ) {
        val now = Clock.System.now()
        repo.seed(
            Task(
                id = TaskId.fromString(id),
                title = title,
                userId = testUserId,
                completedAt = completedAt,
                archivedAt = archivedAt,
                isPinned = isPinned,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    // ─── ChecklistRepository ───────────────────────────────────────────────────

    @Test
    fun `ChecklistRepository addItem → item persisted with isCompleted false`() = runTest {
        val taskRepo = makeTaskRepo()
        val checklistRepo = makeChecklistRepo()
        val taskId = TaskId.fromString("t-checklist")

        taskRepo.seed(
            Task(
                id = taskId,
                userId = testUserId,
                title = "Shopping",
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            ),
        )

        val itemId = checklistRepo.addItem(taskId.value, "Milk").getOrThrow()
        advanceUntilIdle()

        val items = checklistRepo.watchByTask(taskId.value).first()
        assertEquals(1, items.size)
        assertEquals("Milk", items[0].title)
        assertFalse(items[0].isCompleted)
    }

    @Test
    fun `ChecklistRepository add two items → both persisted`() = runTest {
        val taskRepo = makeTaskRepo()
        val checklistRepo = makeChecklistRepo()
        val taskId = TaskId.fromString("t-multi")

        taskRepo.seed(
            Task(
                id = taskId,
                userId = testUserId,
                title = "Multi",
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            ),
        )

        checklistRepo.addItem(taskId.value, "Milk").getOrThrow()
        checklistRepo.addItem(taskId.value, "Bread").getOrThrow()
        advanceUntilIdle()

        val items = checklistRepo.watchByTask(taskId.value).first()
        assertEquals(2, items.size)
    }

    @Test
    fun `ChecklistRepository toggleItem → flips isCompleted`() = runTest {
        val taskRepo = makeTaskRepo()
        val checklistRepo = makeChecklistRepo()
        val taskId = TaskId.fromString("t-toggle")

        taskRepo.seed(
            Task(
                id = taskId,
                userId = testUserId,
                title = "Toggle test",
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            ),
        )

        val itemId = checklistRepo.addItem(taskId.value, "Step 1").getOrThrow()
        advanceUntilIdle()

        val before = checklistRepo.watchByTask(taskId.value).first().first()
        assertFalse(before.isCompleted)

        checklistRepo.toggleItem(taskId.value, itemId, "user").getOrThrow()
        advanceUntilIdle()

        val after = checklistRepo.watchByTask(taskId.value).first().first()
        assertTrue(after.isCompleted)
    }

    @Test
    fun `ChecklistRepository deleteItem → removes item`() = runTest {
        val taskRepo = makeTaskRepo()
        val checklistRepo = makeChecklistRepo()
        val taskId = TaskId.fromString("t-del")

        taskRepo.seed(
            Task(
                id = taskId,
                userId = testUserId,
                title = "Delete test",
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now(),
            ),
        )

        val itemId = checklistRepo.addItem(taskId.value, "Temp item").getOrThrow()
        advanceUntilIdle()

        checklistRepo.delete(itemId).getOrThrow()
        advanceUntilIdle()

        val items = checklistRepo.watchByTask(taskId.value).first()
        assertTrue(items.isEmpty())
    }
}

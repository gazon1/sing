package com.singularity.todo.feature.tasks

import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.checklist.ChecklistUseCase
import com.singularity.todo.feature.tasks.usecase.BulkCompleteUseCase
import com.singularity.todo.feature.tasks.usecase.BulkDeleteUseCase
import com.singularity.todo.feature.tasks.usecase.DeleteTaskUseCase
import com.singularity.todo.feature.tasks.usecase.ToggleTaskUseCase
import com.singularity.todo.feature.tasks.usecase.TogglePinUseCase
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeCurrentUser
import com.singularity.todo.test.fakes.FakeReminderRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        completedAt: kotlinx.datetime.Instant? = null,
        archivedAt: kotlinx.datetime.Instant? = null,
        isPinned: Boolean = false,
    ) {
        val now = Clock.now()
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
            )
        )
    }

    // ─── ToggleTaskUseCase ───────────────────────────────────────────────────

    @Test
    fun `ToggleTaskUseCase completes an incomplete task`() = runTest {
        val repo = makeTaskRepo()
        seed(repo, "t1", "Buy milk")
        val useCase = ToggleTaskUseCase(repo)

        useCase(TaskId.fromString("t1"))
        advanceUntilIdle()

        val task = repo.tasks.value["t1"]
        assertTrue(task?.isCompleted == true)
    }

    @Test
    fun `ToggleTaskUseCase uncompletes a completed task`() = runTest {
        val repo = makeTaskRepo()
        seed(repo, "t1", "Done", completedAt = Clock.now())
        val useCase = ToggleTaskUseCase(repo)

        useCase(TaskId.fromString("t1"))
        advanceUntilIdle()

        val task = repo.tasks.value["t1"]
        assertFalse(task?.isCompleted == true)
    }

    // ─── DeleteTaskUseCase ──────────────────────────────────────────────────

    @Test
    fun `DeleteTaskUseCase soft-deletes by setting archivedAt`() = runTest {
        val repo = makeTaskRepo()
        seed(repo, "t1", "To delete")
        val useCase = DeleteTaskUseCase(repo)

        useCase(TaskId.fromString("t1"))
        advanceUntilIdle()

        val task = repo.tasks.value["t1"]
        assertNotNull(task?.archivedAt)
    }

    // ─── BulkCompleteUseCase ───────────────────────────────────────────────

    @Test
    fun `BulkCompleteUseCase marks all tasks done`() = runTest {
        val repo = makeTaskRepo()
        val now = Clock.now()
        repo.seed(
            Task(
                id = TaskId.fromString("t1"),
                title = "Task 1",
                userId = testUserId,
                createdAt = now,
                updatedAt = now,
            ),
            Task(
                id = TaskId.fromString("t2"),
                title = "Task 2",
                userId = testUserId,
                createdAt = now,
                updatedAt = now,
            )
        )
        val useCase = BulkCompleteUseCase(repo)

        useCase(listOf(TaskId.fromString("t1"), TaskId.fromString("t2")))
        advanceUntilIdle()

        val t1 = repo.tasks.value["t1"]
        val t2 = repo.tasks.value["t2"]
        assertNotNull(t1, "t1 should exist but keys=${repo.tasks.value.keys}")
        assertNotNull(t2, "t2 should exist")
        assertTrue(t1.isCompleted, "t1.isCompleted=${t1.isCompleted}")
        assertTrue(t2.isCompleted, "t2.isCompleted=${t2.isCompleted}")
    }

    // ─── TogglePinUseCase ───────────────────────────────────────────────────

    @Test
    fun `TogglePinUseCase flips isPinned flag`() = runTest {
        val repo = makeTaskRepo()
        seed(repo, "t1", "Important", isPinned = false)
        val useCase = TogglePinUseCase(repo)

        useCase(TaskId.fromString("t1"))
        advanceUntilIdle()

        val task = repo.tasks.value["t1"]
        assertTrue(task?.isPinned == true)
    }

    // ─── ChecklistUseCase ───────────────────────────────────────────────────

    @Test
    fun `ChecklistUseCase addItem → item persisted with isCompleted false`() = runTest {
        val taskRepo = makeTaskRepo()
        val checklistRepo = makeChecklistRepo()
        val checklistUseCase = ChecklistUseCase(checklistRepo, Clock)
        val taskId = TaskId.fromString("t-checklist")

        taskRepo.seed(Task(
            id = taskId,
            userId = testUserId,
            title = "Shopping",
            createdAt = Clock.now(),
            updatedAt = Clock.now(),
        ))

        val itemId = checklistUseCase.addItem(taskId.value, "Milk").getOrThrow()
        advanceUntilIdle()

        val items = checklistUseCase.watchChecklist(taskId.value).first()
        assertEquals(1, items.size)
        assertEquals("Milk", items[0].title)
        assertFalse(items[0].isCompleted)
    }

    @Test
    fun `ChecklistUseCase add two items → both persisted`() = runTest {
        val taskRepo = makeTaskRepo()
        val checklistRepo = makeChecklistRepo()
        val checklistUseCase = ChecklistUseCase(checklistRepo, Clock)
        val taskId = TaskId.fromString("t-multi")

        taskRepo.seed(Task(
            id = taskId,
            userId = testUserId,
            title = "Multi",
            createdAt = Clock.now(),
            updatedAt = Clock.now(),
        ))

        checklistUseCase.addItem(taskId.value, "Milk")
        checklistUseCase.addItem(taskId.value, "Bread")
        advanceUntilIdle()

        val items = checklistUseCase.watchChecklist(taskId.value).first()
        assertEquals(2, items.size)
    }

    @Test
    fun `ChecklistUseCase toggleItem → flips isCompleted`() = runTest {
        val taskRepo = makeTaskRepo()
        val checklistRepo = makeChecklistRepo()
        val checklistUseCase = ChecklistUseCase(checklistRepo, Clock)
        val taskId = TaskId.fromString("t-toggle")

        taskRepo.seed(Task(
            id = taskId,
            userId = testUserId,
            title = "Toggle test",
            createdAt = Clock.now(),
            updatedAt = Clock.now(),
        ))

        val itemId = checklistUseCase.addItem(taskId.value, "Step 1").getOrThrow()
        advanceUntilIdle()

        val before = checklistUseCase.watchChecklist(taskId.value).first().first()
        assertFalse(before.isCompleted)

        checklistUseCase.toggleItem(taskId.value, itemId)
        advanceUntilIdle()

        val after = checklistUseCase.watchChecklist(taskId.value).first().first()
        assertTrue(after.isCompleted)
    }

    @Test
    fun `ChecklistUseCase deleteItem → removes item`() = runTest {
        val taskRepo = makeTaskRepo()
        val checklistRepo = makeChecklistRepo()
        val checklistUseCase = ChecklistUseCase(checklistRepo, Clock)
        val taskId = TaskId.fromString("t-del")

        taskRepo.seed(Task(
            id = taskId,
            userId = testUserId,
            title = "Delete test",
            createdAt = Clock.now(),
            updatedAt = Clock.now(),
        ))

        val itemId = checklistUseCase.addItem(taskId.value, "Temp item").getOrThrow()
        advanceUntilIdle()

        checklistUseCase.deleteItem(itemId)
        advanceUntilIdle()

        val items = checklistUseCase.watchChecklist(taskId.value).first()
        assertTrue(items.isEmpty())
    }
}

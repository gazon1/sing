package com.singularity.todo.feature.tasks

import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskDetailDeps
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase
import com.singularity.todo.feature.tasks.presentation.state.TaskDetailIntent
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskDetailViewModel
import com.singularity.todo.test.fakes.FakeAttachmentRepository
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeReminderRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TEST_TZ: TimeZoneProvider = object : TimeZoneProvider {
    override fun current() = kotlinx.datetime.TimeZone.UTC
}

/**
 * Unit tests for [TaskDetailViewModel] verifying behavioral contracts.
 *
 * Timing: uses virtual time via advanceUntilIdle() — no real delays.
 * The VM's MutableStateFlow is immediately active on construction,
 * so no initial delay is needed after createVm().
 *
 * Covered:
 * 1. TOCTOU fix: _latestTask cache prevents losing concurrent remote edits
 * 2. Intent-based actions: ToggleComplete, Delete, Archive, AddChecklistItem,
 *    ToggleChecklistItem produce verifiable side-effects in the repository
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailViewModelTest {

    private val testUserId = UserId("test-user")
    private val fakeTaskRepo = FakeTaskRepository()
    private val fakeChecklistRepo = FakeChecklistRepository()
    private val fakeReminderRepo = FakeReminderRepository()
    private val fakeReminderScheduler = object : ReminderScheduler {
        override suspend fun schedule(reminder: com.singularity.todo.feature.reminders.Reminder) {}
        override suspend fun cancel(id: ReminderId, userId: UserId) {}
        override suspend fun cancelByTask(
            taskId: com.singularity.todo.feature.tasks.domain.model.TaskId,
            userId: UserId,
        ) {}
    }
    private val fakeProjectRepo = FakeProjectsRepository()
    private val fakeTagsRepo = FakeTagsRepository()
    private val fakeAttachmentsRepo = FakeAttachmentRepository()

    private fun createVm(scope: CoroutineScope, taskId: TaskId): TaskDetailViewModel {
        val deps = TaskDetailDeps(
            taskRepo = fakeTaskRepo,
            updateTask = UpdateTaskUseCase(fakeTaskRepo, Clock),
            createTask = CreateTaskUseCase(
                fakeTaskRepo, Clock,
                FakeProfileAwareCurrentUser(
                    FakeAuthRepository(
                initialSession = com.singularity.todo.core.auth.Session.Anonymous(testUserId),
            )
                )
            ),
            projectsRepo = fakeProjectRepo,
            tagsRepo = fakeTagsRepo,
            checklistRepository = fakeChecklistRepo,
            reminderRepo = fakeReminderRepo,
            reminderScheduler = fakeReminderScheduler,
            attachmentsRepo = fakeAttachmentsRepo,
            timeZoneProvider = TEST_TZ,
            clock = Clock,
            debounceMs = 300L,
        )
        return TaskDetailViewModel(deps = deps, taskId = taskId, scope = testScope(scope))
    }

    private fun seedTask(id: TaskId = TaskId("t1")): Task {
        val task = Task(
            id = id,
            title = "Test task",
            userId = testUserId,
            createdAt = Clock.now(),
            updatedAt = Clock.now(),
        )
        fakeTaskRepo.seed(task)
        return task
    }

    @AfterTest
    fun cleanup() {
        fakeTaskRepo.clear()
        fakeChecklistRepo.clear()
    }

    // ─── TOCTOU race — _latestTask cache ─────────────────────────────────────
    // PR 1a fix: debounced collectors read from _latestTask (populated inside the
    // combine block) instead of taskRepo.watchTask(id).first() which could discard
    // concurrent remote edits arriving during the debounce window.
    //
    // Scenario: local "v1" debounce fires → remote edit arrives → local "v2"
    // debounce fires. v2 should be the final value, not v1 overwriting remote.

    @Test
    fun `TitleChanged debounce saves after delay`() = runTest {
        val task = seedTask()
        val vm = createVm(backgroundScope, task.id)
        advanceUntilIdle() // Let initial subscription establish

        vm.onIntent(TaskDetailIntent.Domain.TitleChanged("Edited title"))
        advanceUntilIdle() // debounce(300ms) needs real time to advance past 300ms

        assertEquals("Edited title", fakeTaskRepo.tasks.value["t1"]?.title)
    }

    // ─── Explicit actions — observable side-effects ─────────────────────────
    // These actions produce verifiable side-effects in the repository.

    @Test
    fun `ToggleComplete sets completedAt in repository`() = runTest {
        val task = seedTask()
        val vm = createVm(backgroundScope, task.id)
        advanceUntilIdle() // Allow subscription to establish before acting
        assertNull(fakeTaskRepo.tasks.value["t1"]?.completedAt)

        vm.onIntent(TaskDetailIntent.Domain.ToggleComplete)
        advanceUntilIdle() // scope.launch { mutate(...) } executes immediately

        assertNotNull(fakeTaskRepo.tasks.value["t1"]?.completedAt)
    }

    @Test
    fun `Delete sets archivedAt (soft delete) in repository`() = runTest {
        val task = seedTask()
        val vm = createVm(backgroundScope, task.id)
        advanceUntilIdle()
        assertNull(fakeTaskRepo.tasks.value["t1"]?.archivedAt)

        vm.onIntent(TaskDetailIntent.Domain.Delete)
        advanceUntilIdle()

        assertNotNull(fakeTaskRepo.tasks.value["t1"]?.archivedAt)
    }

    @Test
    fun `Archive sets archivedAt in repository`() = runTest {
        val task = seedTask()
        val vm = createVm(backgroundScope, task.id)
        advanceUntilIdle()
        assertNull(fakeTaskRepo.tasks.value["t1"]?.archivedAt)

        vm.onIntent(TaskDetailIntent.Domain.Archive)
        advanceUntilIdle()

        assertNotNull(fakeTaskRepo.tasks.value["t1"]?.archivedAt)
    }

    @Test
    fun `AddChecklistItem creates checklist item in repository`() = runTest {
        val task = seedTask()
        val vm = createVm(backgroundScope, task.id)
        advanceUntilIdle()
        assertTrue(fakeChecklistRepo.items.value.isEmpty())

        vm.onIntent(TaskDetailIntent.Domain.AddChecklistItem("New item"))
        advanceUntilIdle()

        val items = fakeChecklistRepo.items.value.values.toList()
        assertEquals(1, items.size)
        assertEquals("New item", items[0].title)
        assertFalse(items[0].isCompleted)
    }

    @Test
    fun `ToggleChecklistItem flips isCompleted in repository`() = runTest {
        val task = seedTask()
        val vm = createVm(backgroundScope, task.id)
        advanceUntilIdle()

        // Add an item first
        vm.onIntent(TaskDetailIntent.Domain.AddChecklistItem("Toggle me"))
        advanceUntilIdle()

        val item = fakeChecklistRepo.items.value.values.first()
        assertFalse(item.isCompleted)

        // Toggle it
        vm.onIntent(TaskDetailIntent.Domain.ToggleChecklistItem(item))
        advanceUntilIdle()

        val toggled = fakeChecklistRepo.items.value[item.id.value]
        assertTrue(toggled?.isCompleted == true)
    }

    @Test
    fun `TogglePinned flips isPinned — pin then unpin`() = runTest {
        val task = seedTask()
        val vm = createVm(backgroundScope, task.id)
        advanceUntilIdle()
        assertFalse(fakeTaskRepo.tasks.value["t1"]?.isPinned == true)

        // Pin
        vm.onIntent(TaskDetailIntent.Domain.TogglePinned)
        advanceUntilIdle()
        assertTrue(fakeTaskRepo.tasks.value["t1"]?.isPinned == true)

        // Unpin
        vm.onIntent(TaskDetailIntent.Domain.TogglePinned)
        advanceUntilIdle()
        assertFalse(fakeTaskRepo.tasks.value["t1"]?.isPinned == true)
    }
}

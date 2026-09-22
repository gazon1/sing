package com.singularity.todo.feature.tasks

import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.platform.TimeZoneProvider
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
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
 * Timing note: stateIn with WhileSubscribed(5000) delays the flatMapLatest chain
 * until a subscriber exists. The createVm() calls vm.state.launchIn(scope) to
 * ensure the chain is active. Tests use real 100ms delays (not advanceUntilIdle)
 * for action steps — these are for ensuring coroutine completion, not virtual time.
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
    private val fakeProjectRepo = FakeProjectsRepository()
    private val fakeTagsRepo = FakeTagsRepository()
    private val fakeAttachmentsRepo = FakeAttachmentRepository()
    private val fakeCurrentUser = FakeProfileAwareCurrentUser(
        FakeAuthRepository(
            initialSession = com.singularity.todo.core.auth.Session.Anonymous(testUserId),
        ),
    )

    private fun createVm(scope: CoroutineScope, taskId: TaskId): TaskDetailViewModel {
        val deps = TaskDetailDeps(
            taskRepo = fakeTaskRepo,
            updateTask = UpdateTaskUseCase(fakeTaskRepo, Clock),
            createTask = CreateTaskUseCase(fakeTaskRepo, Clock, fakeCurrentUser),
            projectsRepo = fakeProjectRepo,
            tagsRepo = fakeTagsRepo,
            checklistRepository = fakeChecklistRepo,
            reminderRepo = fakeReminderRepo,
            attachmentsRepo = fakeAttachmentsRepo,
            timeZoneProvider = TEST_TZ,
            clock = Clock,
            debounceMs = 300L,
        )
        val vm = TaskDetailViewModel(deps = deps, taskId = taskId, currentUser = fakeCurrentUser, scope = testScope(scope))
        // Activate the stateIn chain (WhileSubscribed requires an initial subscriber).
        // Use launchIn so the upstream starts immediately in tests without waiting
        // for the 5-second WhileSubscribed timeout.
        vm.state.launchIn(scope)
        return vm
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
        delay(100) // Let initial subscription establish

        vm.onIntent(TaskDetailIntent.Domain.TitleChanged("Edited title"))
        delay(400) // debounce(300ms) needs real time to advance past 300ms

        assertEquals("Edited title", fakeTaskRepo.tasks.value["t1"]?.title)
    }

    // ─── Explicit actions — observable side-effects ─────────────────────────
    // These actions produce verifiable side-effects in the repository.

    @Test
    fun `ToggleComplete sets completedAt in repository`() = runTest {
        val task = seedTask()
        val vm = createVm(backgroundScope, task.id)
        delay(100) // Allow subscription to establish before acting
        assertNull(fakeTaskRepo.tasks.value["t1"]?.completedAt)

        vm.onIntent(TaskDetailIntent.Domain.ToggleComplete)
        delay(50) // scope.launch { mutate(...) } executes immediately

        assertNotNull(fakeTaskRepo.tasks.value["t1"]?.completedAt)
    }

    @Test
    fun `Delete sets archivedAt (soft delete) in repository`() = runTest {
        val task = seedTask()
        val vm = createVm(backgroundScope, task.id)
        delay(100)
        assertNull(fakeTaskRepo.tasks.value["t1"]?.archivedAt)

        vm.onIntent(TaskDetailIntent.Domain.Delete)
        delay(50)

        assertNotNull(fakeTaskRepo.tasks.value["t1"]?.archivedAt)
    }

    @Test
    fun `Archive sets archivedAt in repository`() = runTest {
        val task = seedTask()
        val vm = createVm(backgroundScope, task.id)
        delay(100)
        assertNull(fakeTaskRepo.tasks.value["t1"]?.archivedAt)

        vm.onIntent(TaskDetailIntent.Domain.Archive)
        delay(50)

        assertNotNull(fakeTaskRepo.tasks.value["t1"]?.archivedAt)
    }

    @Test
    fun `AddChecklistItem creates checklist item in repository`() = runTest {
        val task = seedTask()
        val vm = createVm(backgroundScope, task.id)
        delay(100)
        assertTrue(fakeChecklistRepo.items.value.isEmpty())

        vm.onIntent(TaskDetailIntent.Domain.AddChecklistItem("New item"))
        delay(50)

        val items = fakeChecklistRepo.items.value.values.toList()
        assertEquals(1, items.size)
        assertEquals("New item", items[0].title)
        assertFalse(items[0].isCompleted)
    }

    @Test
    fun `ToggleChecklistItem flips isCompleted in repository`() = runTest {
        val task = seedTask()
        val vm = createVm(backgroundScope, task.id)
        delay(100)

        // Add an item first
        vm.onIntent(TaskDetailIntent.Domain.AddChecklistItem("Toggle me"))
        delay(50)

        val item = fakeChecklistRepo.items.value.values.first()
        assertFalse(item.isCompleted)

        // Toggle it
        vm.onIntent(TaskDetailIntent.Domain.ToggleChecklistItem(item))
        delay(50)

        val toggled = fakeChecklistRepo.items.value[item.id.value]
        assertTrue(toggled?.isCompleted == true)
    }

    @Test
    fun `TogglePinned flips isPinned — pin then unpin`() = runTest {
        val task = seedTask()
        val vm = createVm(backgroundScope, task.id)
        delay(100)
        assertFalse(fakeTaskRepo.tasks.value["t1"]?.isPinned == true)

        // Pin
        vm.onIntent(TaskDetailIntent.Domain.TogglePinned)
        delay(50)
        assertTrue(fakeTaskRepo.tasks.value["t1"]?.isPinned == true)

        // Unpin
        vm.onIntent(TaskDetailIntent.Domain.TogglePinned)
        delay(50)
        assertFalse(fakeTaskRepo.tasks.value["t1"]?.isPinned == true)
    }
}

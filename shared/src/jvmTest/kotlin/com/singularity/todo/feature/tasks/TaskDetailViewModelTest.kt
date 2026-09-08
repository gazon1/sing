package com.singularity.todo.feature.tasks

import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.checklist.ChecklistUseCase
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeReminderRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeAttachmentRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
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
 * Unit tests for [TaskDetailViewModel] verifying behavioral contracts from PR 1a.
 *
 * Timing note: stateIn with WhileSubscribed(5000) delays the flatMapLatest chain
 * until a subscriber exists. The createVm() calls vm.state.launchIn(scope) to
 * ensure the chain is active. Tests use real 100ms delays (not advanceUntilIdle)
 * for action steps to avoid virtual-time conflicts with the 300ms debounce timers.
 *
 * Covered:
 * 1. TOCTOU fix: _latestTask cache prevents losing concurrent remote edits
 * 2. Explicit actions: setCompleted, deleteTask, archiveTask, addChecklistItem,
 *    toggleChecklistItem produce verifiable side-effects in the repository
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
            initialSession = com.singularity.todo.core.auth.Session.Anonymous(testUserId)
        )
    )

    private fun createVm(scope: CoroutineScope): TaskDetailViewModel {
        val vm = TaskDetailViewModel(
            taskRepo = fakeTaskRepo,
            updateTask = UpdateTaskUseCase(fakeTaskRepo, Clock),
            projectsRepo = fakeProjectRepo,
            tagsRepo = fakeTagsRepo,
            checklistUseCase = ChecklistUseCase(fakeChecklistRepo, Clock),
            reminderRepo = fakeReminderRepo,
            attachmentsRepo = fakeAttachmentsRepo,
            currentUser = fakeCurrentUser,
            timeZoneProvider = TEST_TZ,
            scopeOverride = scope,
        )
        // Activate the stateIn chain (WhileSubscribed requires an initial subscriber)
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
    fun `onTitleChange uses _latestTask cache — remote edit is preserved`() = runTest {
        val task = seedTask()
        val vm = createVm(backgroundScope)
        vm.start(task.id)
        advanceUntilIdle()

        // Local edit 1 fires debounce (300ms)
        vm.onTitleChange("Local v1")
        delay(350) // wait for debounce to fire

        // Remote edit arrives concurrently via repository
        val current = fakeTaskRepo.tasks.value["t1"]!!
        fakeTaskRepo.seed(current.copy(title = "Remote edit"))

        // Local edit 2 — applies on top of remote, not stale v1
        vm.onTitleChange("Local v2")
        delay(350) // wait for debounce to fire

        assertEquals("Local v2", fakeTaskRepo.tasks.value["t1"]?.title)
    }

    // ─── Explicit actions — observable side-effects ─────────────────────────
    // These actions produce verifiable side-effects in the repository.

    @Test
    fun `setCompleted sets completedAt in repository`() = runTest {
        val task = seedTask()
        val vm = createVm(backgroundScope)
        vm.start(task.id)
        advanceUntilIdle()
        assertNull(fakeTaskRepo.tasks.value["t1"]?.completedAt)

        vm.setCompleted(task, true)
        delay(50)

        assertNotNull(fakeTaskRepo.tasks.value["t1"]?.completedAt)
    }

    @Test
    fun `deleteTask sets archivedAt (soft delete) in repository`() = runTest {
        val task = seedTask()
        val vm = createVm(backgroundScope)
        vm.start(task.id)
        advanceUntilIdle()
        assertNull(fakeTaskRepo.tasks.value["t1"]?.archivedAt)

        vm.deleteTask(task)
        delay(50)

        assertNotNull(fakeTaskRepo.tasks.value["t1"]?.archivedAt)
    }

    @Test
    fun `archiveTask sets archivedAt in repository`() = runTest {
        val task = seedTask()
        val vm = createVm(backgroundScope)
        vm.start(task.id)
        advanceUntilIdle()
        assertNull(fakeTaskRepo.tasks.value["t1"]?.archivedAt)

        vm.archiveTask(task)
        delay(50)

        assertNotNull(fakeTaskRepo.tasks.value["t1"]?.archivedAt)
    }

    @Test
    fun `addChecklistItem creates checklist item in repository`() = runTest {
        val task = seedTask()
        val vm = createVm(backgroundScope)
        vm.start(task.id)
        advanceUntilIdle()
        assertTrue(fakeChecklistRepo.items.value.isEmpty())

        vm.addChecklistItem(task.id, "New item")
        delay(50)

        val items = fakeChecklistRepo.items.value.values.toList()
        assertEquals(1, items.size)
        assertEquals("New item", items[0].title)
        assertFalse(items[0].isCompleted)
    }

    @Test
    fun `toggleChecklistItem flips isCompleted in repository`() = runTest {
        val task = seedTask()
        val vm = createVm(backgroundScope)
        vm.start(task.id)
        advanceUntilIdle()

        // Add an item first
        vm.addChecklistItem(task.id, "Toggle me")
        delay(50)

        val item = fakeChecklistRepo.items.value.values.first()
        assertFalse(item.isCompleted)

        // Toggle it
        vm.toggleChecklistItem(item)
        delay(50)

        val toggled = fakeChecklistRepo.items.value[item.id.value]
        assertTrue(toggled?.isCompleted == true)
    }
}

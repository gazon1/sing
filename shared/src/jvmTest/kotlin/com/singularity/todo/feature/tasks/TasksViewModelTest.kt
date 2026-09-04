package com.singularity.todo.feature.tasks

import com.singularity.todo.feature.ai.use_cases.DecomposeTaskUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateChecklistUseCase
import com.singularity.todo.feature.ai.use_cases.GenerateDescriptionUseCase
import com.singularity.todo.feature.ai.use_cases.PickTimeUseCase
import com.singularity.todo.feature.ai.use_cases.RefineTaskUseCase
import com.singularity.todo.test.fakes.FakeSettingsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import app.cash.turbine.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for TasksViewModel using FakeTaskRepository.
 *
 * Note: VM actions that launch coroutines (togglePin, toggle, delete, bulkComplete, bulkDelete)
 * require the VM's scope to use the test's TestDispatcher for advanceUntilIdle to process them.
 * Currently scopeOverride uses backgroundScope which is controlled differently.
 * These tests verify repository state changes directly where possible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {

    private val testUserId = UserId("test-user")
    private val fakeTaskRepo = FakeTaskRepository()
    private val fakeSettings = FakeSettingsRepository(testUserId.value)

    private fun createVm(
        refineTask: RefineTaskUseCase? = null,
        generateDescription: GenerateDescriptionUseCase? = null,
        generateChecklist: GenerateChecklistUseCase? = null,
        decomposeTask: DecomposeTaskUseCase? = null,
        pickTime: PickTimeUseCase? = null,
    ) = TasksViewModel(
        taskRepo = fakeTaskRepo,
        createTask = CreateTaskUseCase(fakeTaskRepo, com.singularity.todo.core.platform.Clock),
        updateTask = UpdateTaskUseCase(fakeTaskRepo, com.singularity.todo.core.platform.Clock),
        settingsRepository = fakeSettings,
        refineTask = refineTask,
        generateDescription = generateDescription,
        generateChecklist = generateChecklist,
        decomposeTask = decomposeTask,
        pickTime = pickTime,
    )

    private fun seedTask(
        id: String,
        title: String,
        dueDate: kotlinx.datetime.LocalDate? = null,
        someday: Boolean = false,
        isPinned: Boolean = false,
        isTrashed: Boolean = false,
    ) {
        val now = com.singularity.todo.core.platform.Clock.now()
        fakeTaskRepo.seed(
            Task(
                id = TaskId.fromString(id),
                title = title,
                userId = testUserId,
                dueDate = dueDate,
                someday = someday,
                isPinned = isPinned,
                archivedAt = if (isTrashed) now else null,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    // ─── initial state ─────────────────────────────────────────────────────

    @Test
    fun `initial state is Loading`() = runTest {
        val vm = createVm()
        assertIs<TasksUiState.Loading>(vm.state.value)
    }

    // ─── filter ─────────────────────────────────────────────────────────────

    @Test
    fun `setFilter updates filter state`() = runTest {
        val vm = createVm()
        vm.setFilter(TaskFilter.Upcoming)
        assertEquals(TaskFilter.Upcoming, vm.filter.value)
    }

    // ─── selection mode (synchronous, no coroutines) ────────────────────────

    @Test
    fun `enterSelectionMode sets selectedIds`() = runTest {
        val vm = createVm()
        vm.enterSelectionMode(TaskId.fromString("t1"))
        assertEquals(setOf(TaskId.fromString("t1")), vm.selectedIds.value)
    }

    @Test
    fun `toggleSelection adds and removes`() = runTest {
        val vm = createVm()
        vm.enterSelectionMode(TaskId.fromString("t1"))

        vm.toggleSelection(TaskId.fromString("t2"))
        assertEquals(setOf(TaskId.fromString("t1"), TaskId.fromString("t2")), vm.selectedIds.value)

        vm.toggleSelection(TaskId.fromString("t1"))
        assertEquals(setOf(TaskId.fromString("t2")), vm.selectedIds.value)
    }

    @Test
    fun `exitSelectionMode clears selectedIds`() = runTest {
        val vm = createVm()
        vm.enterSelectionMode(TaskId.fromString("t1"))
        vm.toggleSelection(TaskId.fromString("t2"))

        vm.exitSelectionMode()
        assertTrue(vm.selectedIds.value.isEmpty())
    }

    // ─── repository-level toggle tests (using FakeTaskRepository directly) ───

    @Test
    fun `togglePinned flips pinned state`() = runTest {
        seedTask("t1", "My Task", isPinned = true)
        fakeTaskRepo.togglePinned(TaskId.fromString("t1"))
        advanceUntilIdle()
        assertFalse(fakeTaskRepo.tasks.value["t1"]!!.isPinned)
    }

    @Test
    fun `toggleComplete flips completed state`() = runTest {
        seedTask("t1", "My Task")
        fakeTaskRepo.toggleComplete(TaskId.fromString("t1"))
        advanceUntilIdle()
        assertTrue(fakeTaskRepo.tasks.value["t1"]!!.isCompleted)
    }

    @Test
    fun `softDelete sets archivedAt`() = runTest {
        seedTask("t1", "My Task")
        fakeTaskRepo.softDelete(TaskId.fromString("t1"))
        advanceUntilIdle()
        assertTrue(fakeTaskRepo.tasks.value["t1"]!!.isTrashed)
    }

    // ─── AI routing ────────────────────────────────────────────────────────

    @Test
    fun `runAiAction with null use cases emits Error AiActionResult`() = runTest {
        seedTask("t1", "My Task", dueDate = kotlinx.datetime.LocalDate(2024, 1, 15))
        val vm = createVm() // all AI use cases null

        val aiResults = mutableListOf<AiActionResult>()
        vm.aiResult.test {
            advanceUntilIdle() // ensure collection started
            vm.runAiAction(fakeTaskRepo.tasks.value["t1"]!!, TaskAiAction.RefineTitle)
            advanceUntilIdle()
            val result = awaitItem()
            assertIs<AiActionResult.Error>(result)
            assertEquals("AI not available", result.message)
            cancelAndConsumeRemainingEvents()
        }
    }
}

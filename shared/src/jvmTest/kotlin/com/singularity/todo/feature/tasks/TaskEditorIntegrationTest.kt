package com.singularity.todo.feature.tasks

import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ids.SequenceIdGenerator
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.platform.systemTimeZone
import com.singularity.todo.feature.checklist.ChecklistUseCase
import com.singularity.todo.feature.tasks.TaskEditorDeps
import com.singularity.todo.feature.tasks.UpdateTaskUseCase
import com.singularity.todo.feature.settings.ReminderOffset
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeReminderRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Integration test for TaskEditor — verifies the full save flow
 * from title input → validation → task creation in repository.
 *
 * Uses Koin with Fake* repositories to simulate the full stack
 * without any platform dependencies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskEditorIntegrationTest {

    private val testUserId = UserId("test-user")
    private val fakeTaskRepo = FakeTaskRepository()
    private val fakeChecklistRepo = FakeChecklistRepository()
    private val fakeReminderRepo = FakeReminderRepository()
    private val fakeAuth = FakeAuthRepository(
        com.singularity.todo.core.auth.Session.Anonymous(testUserId)
    )
    private val fakeCurrentUser = FakeProfileAwareCurrentUser(fakeAuth)
    private val idGen: IdGenerator = SequenceIdGenerator()
    private val tz: TimeZoneProvider = object : TimeZoneProvider {
        override fun current() = kotlinx.datetime.TimeZone.UTC
    }

    private fun createVm(
        initialDueDate: kotlinx.datetime.LocalDate? = null,
        scope: kotlinx.coroutines.CoroutineScope? = null,
    ): TaskEditorViewModel {
        return TaskEditorViewModel(
            deps = TaskEditorDeps(
                createTask = CreateTaskUseCase(fakeTaskRepo, Clock),
                clock = Clock,
                currentUser = fakeCurrentUser,
                checklistUseCase = ChecklistUseCase(fakeChecklistRepo, Clock),
                reminderRepository = fakeReminderRepo,
                attachmentSaver = FakeAttachmentSaver,
                idGen = idGen,
                timeZoneProvider = tz,
                updateTask = UpdateTaskUseCase(fakeTaskRepo, Clock),
                taskRepository = fakeTaskRepo,
            ),
            initialDueDate = initialDueDate,
            scopeOverride = scope,
        )
    }

    @Test
    fun `valid title saves task to repository`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.TitleChanged("Buy groceries"))
        advanceUntilIdle()

        vm.onIntent(TaskEditorIntent.Save)
        advanceUntilIdle()

        assertFalse(fakeTaskRepo.tasks.value.isEmpty())
        val saved = fakeTaskRepo.tasks.value.values.first()
        assertEquals("Buy groceries", saved.title)
    }

    @Test
    fun `blank title sets errorMessage and does not save`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.TitleChanged("   "))
        advanceUntilIdle()

        vm.onIntent(TaskEditorIntent.Save)
        advanceUntilIdle()

        assertNotEquals(null, vm.uiState.value.errorMessage)
        assertTrue(fakeTaskRepo.tasks.value.isEmpty())
    }

    @Test
    fun `errorShown clears errorMessage`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.TitleChanged("   "))
        advanceUntilIdle()
        vm.onIntent(TaskEditorIntent.Save)
        advanceUntilIdle()
        assertNotEquals(null, vm.uiState.value.errorMessage)

        vm.onIntent(TaskEditorIntent.ErrorShown)
        assertEquals(null, vm.uiState.value.errorMessage)
    }

    @Test
    fun `checklist items are preserved in state`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.TitleChanged("New Task"))
        vm.onIntent(TaskEditorIntent.NewChecklistItemChanged("Step 1"))
        vm.onIntent(TaskEditorIntent.AddChecklistItem)
        vm.onIntent(TaskEditorIntent.NewChecklistItemChanged("Step 2"))
        vm.onIntent(TaskEditorIntent.AddChecklistItem)
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.checklistItems.size)
        assertEquals("Step 1", vm.uiState.value.checklistItems[0].title)
        assertEquals("Step 2", vm.uiState.value.checklistItems[1].title)
    }

    @Test
    fun `reminder offset is stored in state`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.ReminderOffsetChanged(ReminderOffset.ONE_HOUR))
        assertEquals(ReminderOffset.ONE_HOUR, vm.uiState.value.reminderOffset)
    }

    @Test
    fun `due date and time are stored in state`() = runTest {
        val vm = createVm(scope = backgroundScope)
        val date = kotlinx.datetime.LocalDate(2025, 7, 15)
        val time = kotlinx.datetime.LocalTime(14, 30)
        vm.onIntent(TaskEditorIntent.DueDateChanged(date))
        vm.onIntent(TaskEditorIntent.DueTimeChanged(time))
        assertEquals(date, vm.uiState.value.dueDate)
        assertEquals(time, vm.uiState.value.dueTime)
    }

    @Test
    fun `pending attachment is added to state`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.AddAttachment("/tmp/file.pdf", "file.pdf", "application/pdf"))
        assertEquals(1, vm.uiState.value.pendingAttachments.size)
        assertEquals("/tmp/file.pdf", vm.uiState.value.pendingAttachments[0].path)
    }
}

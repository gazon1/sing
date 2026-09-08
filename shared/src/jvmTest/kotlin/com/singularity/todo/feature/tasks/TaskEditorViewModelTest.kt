package com.singularity.todo.feature.tasks

import com.singularity.todo.core.ids.IdGenerator
import com.singularity.todo.core.ids.SequenceIdGenerator
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.checklist.ChecklistUseCase
import com.singularity.todo.feature.tasks.TaskEditorDeps
import com.singularity.todo.feature.tasks.UpdateTaskUseCase
import com.singularity.todo.feature.settings.ReminderOffset
import com.singularity.todo.feature.tasks.FakeAttachmentSaver
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeReminderRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val TEST_TZ: TimeZoneProvider = object : TimeZoneProvider {
    override fun current(): kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.UTC
}

@OptIn(ExperimentalCoroutinesApi::class)
class TaskEditorViewModelTest {

    private val testUserId = UserId("test-user")
    private val fakeTaskRepo = FakeTaskRepository()
    private val fakeChecklistRepo = FakeChecklistRepository()
    private val fakeReminderRepo = FakeReminderRepository()
    private val fakeCurrentUser = FakeProfileAwareCurrentUser(
        FakeAuthRepository(
            initialSession = com.singularity.todo.core.auth.Session.Anonymous(testUserId)
        )
    )

    private val savedAttachments = mutableListOf<Triple<String, String, String?>>()

    private fun createVm(
        initialDueDate: kotlinx.datetime.LocalDate? = null,
        scope: CoroutineScope? = null,
        idGen: IdGenerator = SequenceIdGenerator(),
        timeZoneProvider: TimeZoneProvider = TEST_TZ,
        attachmentSaver: AttachmentSaver = FakeAttachmentSaver,
    ): TaskEditorViewModel {
        // Use the platform Clock singleton directly
        val clock = Clock
        return TaskEditorViewModel(
            deps = TaskEditorDeps(
                createTask = CreateTaskUseCase(fakeTaskRepo, clock),
                clock = clock,
                currentUser = fakeCurrentUser,
                checklistUseCase = ChecklistUseCase(fakeChecklistRepo, clock),
                reminderRepository = fakeReminderRepo,
                attachmentSaver = attachmentSaver,
                idGen = idGen,
                timeZoneProvider = timeZoneProvider,
                updateTask = UpdateTaskUseCase(fakeTaskRepo, clock),
                taskRepository = fakeTaskRepo,
            ),
            initialDueDate = initialDueDate,
            scopeOverride = scope,
        )
    }

    // ─── Title ─────────────────────────────────────────────────────────────

    @Test
    fun `TitleChanged updates title`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.TitleChanged("Buy groceries"))
        assertEquals("Buy groceries", vm.uiState.value.title)
    }

    @Test
    fun `TitleChanged clears errorMessage`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.TitleChanged(""))
        advanceUntilIdle()
        vm.onIntent(TaskEditorIntent.Save)
        advanceUntilIdle()
        assertNotEquals(null, vm.uiState.value.errorMessage)

        vm.onIntent(TaskEditorIntent.TitleChanged("Valid title"))
        assertEquals(null, vm.uiState.value.errorMessage)
    }

    // ─── Description ─────────────────────────────────────────────────────

    @Test
    fun `DescriptionChanged updates description`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.DescriptionChanged("Do this and that"))
        assertEquals("Do this and that", vm.uiState.value.description)
    }

    // ─── Due date / time ────────────────────────────────────────────────

    @Test
    fun `DueDateChanged updates dueDate`() = runTest {
        val vm = createVm(scope = backgroundScope)
        val date = kotlinx.datetime.LocalDate(2025, 6, 15)
        vm.onIntent(TaskEditorIntent.DueDateChanged(date))
        assertEquals(date, vm.uiState.value.dueDate)
    }

    @Test
    fun `DueTimeChanged updates dueTime`() = runTest {
        val vm = createVm(scope = backgroundScope)
        val time = kotlinx.datetime.LocalTime(14, 30)
        vm.onIntent(TaskEditorIntent.DueTimeChanged(time))
        assertEquals(time, vm.uiState.value.dueTime)
    }

    // ─── Checklist ──────────────────────────────────────────────────────

    @Test
    fun `AddChecklistItem adds item when text is non-blank`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.NewChecklistItemChanged("Milk"))
        vm.onIntent(TaskEditorIntent.AddChecklistItem)
        val items = vm.uiState.value.checklistItems
        assertEquals(1, items.size)
        assertEquals("Milk", items.first().title)
        assertFalse(items.first().isCompleted)
    }

    @Test
    fun `AddChecklistItem ignores blank text`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.NewChecklistItemChanged("   "))
        vm.onIntent(TaskEditorIntent.AddChecklistItem)
        assertTrue(vm.uiState.value.checklistItems.isEmpty())
    }

    @Test
    fun `ToggleChecklistItem toggles completion`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.NewChecklistItemChanged("Milk"))
        vm.onIntent(TaskEditorIntent.AddChecklistItem)
        val id = vm.uiState.value.checklistItems.first().id
        assertFalse(vm.uiState.value.checklistItems.first().isCompleted)

        vm.onIntent(TaskEditorIntent.ToggleChecklistItem(id))
        assertTrue(vm.uiState.value.checklistItems.first().isCompleted)

        vm.onIntent(TaskEditorIntent.ToggleChecklistItem(id))
        assertFalse(vm.uiState.value.checklistItems.first().isCompleted)
    }

    @Test
    fun `DeleteChecklistItem removes item`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.NewChecklistItemChanged("Milk"))
        vm.onIntent(TaskEditorIntent.AddChecklistItem)
        val id = vm.uiState.value.checklistItems.first().id

        vm.onIntent(TaskEditorIntent.DeleteChecklistItem(id))
        assertTrue(vm.uiState.value.checklistItems.isEmpty())
    }

    // ─── Reminder ──────────────────────────────────────────────────────

    @Test
    fun `ReminderOffsetChanged updates reminderOffset`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.ReminderOffsetChanged(ReminderOffset.FIFTEEN_MIN))
        assertEquals(ReminderOffset.FIFTEEN_MIN, vm.uiState.value.reminderOffset)
    }

    // ─── Attachment ─────────────────────────────────────────────────────

    @Test
    fun `AddAttachment adds pending attachment`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.AddAttachment("/tmp/file.pdf", "file.pdf", "application/pdf"))
        val attachments = vm.uiState.value.pendingAttachments
        assertEquals(1, attachments.size)
        assertEquals("/tmp/file.pdf", attachments.first().path)
        assertEquals("file.pdf", attachments.first().name)
    }

    // ─── Save — success ────────────────────────────────────────────────

    @Test
    fun `Save with valid title creates task`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.TitleChanged("New Task"))
        advanceUntilIdle()

        vm.onIntent(TaskEditorIntent.Save)
        advanceUntilIdle()

        assertFalse(fakeTaskRepo.tasks.value.isEmpty())
        assertEquals("New Task", fakeTaskRepo.tasks.value.values.first().title)
    }

    @Test
    fun `Save with checklist items creates checklist entries`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.TitleChanged("New Task"))
        vm.onIntent(TaskEditorIntent.NewChecklistItemChanged("Step 1"))
        vm.onIntent(TaskEditorIntent.AddChecklistItem)
        vm.onIntent(TaskEditorIntent.NewChecklistItemChanged("Step 2"))
        vm.onIntent(TaskEditorIntent.AddChecklistItem)
        advanceUntilIdle()

        vm.onIntent(TaskEditorIntent.Save)
        advanceUntilIdle()

        val taskId = fakeTaskRepo.tasks.value.values.first().id.value
        val checklistItems = fakeChecklistRepo.items.value.values.filter { it.taskId == taskId }
        assertEquals(2, checklistItems.size)
    }

    @Test
    fun `Save with reminder offset creates reminder`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.TitleChanged("New Task"))
        vm.onIntent(TaskEditorIntent.DueDateChanged(kotlinx.datetime.LocalDate(2025, 6, 15)))
        vm.onIntent(TaskEditorIntent.ReminderOffsetChanged(ReminderOffset.ONE_HOUR))
        advanceUntilIdle()

        vm.onIntent(TaskEditorIntent.Save)
        advanceUntilIdle()

        val taskId = fakeTaskRepo.tasks.value.values.first().id.value
        val reminders = fakeReminderRepo.reminders.value.values.filter { it.taskId.value == taskId }
        assertEquals(1, reminders.size)
    }

    @Test
    fun `Save with pending attachments calls saveAttachment`() = runTest {
        savedAttachments.clear()
        val saver = object : AttachmentSaver {
            override suspend fun save(taskId: TaskId, sourcePath: String, mimeType: String?): Result<Unit> {
                savedAttachments.add(Triple(taskId.value, sourcePath, mimeType))
                return Result.success(Unit)
            }
        }
        val vm = createVm(scope = backgroundScope, attachmentSaver = saver)
        vm.onIntent(TaskEditorIntent.TitleChanged("New Task"))
        vm.onIntent(TaskEditorIntent.AddAttachment("/tmp/file.pdf", "file.pdf", "application/pdf"))
        advanceUntilIdle()

        vm.onIntent(TaskEditorIntent.Save)
        advanceUntilIdle()

        val taskId = fakeTaskRepo.tasks.value.values.first().id.value
        assertEquals(1, savedAttachments.size)
        assertEquals(taskId, savedAttachments[0].first)
        assertEquals("/tmp/file.pdf", savedAttachments[0].second)
    }

    // ─── Save — validation error ────────────────────────────────────────

    @Test
    fun `Save with blank title sets errorMessage`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.TitleChanged("   "))
        advanceUntilIdle()

        vm.onIntent(TaskEditorIntent.Save)
        advanceUntilIdle()

        assertEquals("Title cannot be blank", vm.uiState.value.errorMessage)
        assertTrue(fakeTaskRepo.tasks.value.isEmpty())
    }

    // ─── initialDueDate ─────────────────────────────────────────────────

    @Test
    fun `constructor with initialDueDate pre-fills dueDate`() = runTest {
        val date = kotlinx.datetime.LocalDate(2025, 3, 20)
        val vm = createVm(initialDueDate = date, scope = backgroundScope)
        assertEquals(date, vm.uiState.value.dueDate)
    }

    // ─── Project / Tags ────────────────────────────────────────────────

    @Test
    fun `ProjectChanged updates projectId`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.ProjectChanged("proj-1"))
        assertEquals("proj-1", vm.uiState.value.projectId)
    }

    @Test
    fun `TagsChanged updates tagIds`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.TagsChanged(listOf("tag-1", "tag-2")))
        assertEquals(listOf("tag-1", "tag-2"), vm.uiState.value.tagIds)
    }

    // ─── ErrorShown ───────────────────────────────────────────────────

    @Test
    fun `ErrorShown clears errorMessage`() = runTest {
        val vm = createVm(scope = backgroundScope)
        vm.onIntent(TaskEditorIntent.TitleChanged("   "))
        advanceUntilIdle()
        vm.onIntent(TaskEditorIntent.Save)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.errorMessage != null)

        vm.onIntent(TaskEditorIntent.ErrorShown)
        assertEquals(null, vm.uiState.value.errorMessage)
    }
}

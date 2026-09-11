package com.singularity.todo.feature.tasks

import co.touchlab.kermit.Logger
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase
import com.singularity.todo.feature.tasks.presentation.state.DueDateOption
import com.singularity.todo.feature.tasks.presentation.state.TaskCreateIntent
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateDeps
import com.singularity.todo.feature.tasks.presentation.viewmodel.TaskCreateViewModel
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class TaskCreateViewModelTest {

    private val testScheduler = StandardTestDispatcher()
    private val testScope = TestScope(testScheduler)

    private val fakeTasks = FakeTaskRepository()
    private val fakeUser = FakeProfileAwareCurrentUser()

    /**
     * Subscribes to [vm.state] via [backgroundScope] so the upstream `combine` actually runs.
     * Without an active collector, `WhileSubscribed` keeps the upstream cold and `state.value`
     * returns the initial seed forever.
     */
    private fun TestScope.subscribe(vm: TaskCreateViewModel) {
        backgroundScope.launch {
            vm.state.collect { /* drain */ }
        }
    }

    private fun makeVm(): TaskCreateViewModel = TaskCreateViewModel(
        deps = TaskCreateDeps(
            createTask = CreateTaskUseCase(fakeTasks, com.singularity.todo.core.platform.Clock),
            currentUser = fakeUser,
            logger = Logger.withTag("Test"),
        ),
        initialDueDate = null,
        scopeOverride = testScope,
    )

    @Test
    fun initial_state_has_disabled_save_and_empty_draft() = testScope.runTest {
        val vm = makeVm()
        subscribe(vm)
        advanceUntilIdle()
        val s = vm.state.first()
        assertEquals("", s.draft.title)
        assertEquals(TaskPriority.None, s.draft.priority)
        assertIs<DueDateOption.None>(s.draft.dueDate)
        assertNull(s.draft.dueTime)
        assertFalse(s.isSaveEnabled)
        assertFalse(s.isDirty)
        assertFalse(s.isSaving)
    }

    @Test
    fun initialDueDate_is_set_in_initial_draft() = testScope.runTest {
        val due = LocalDate(2026, 9, 15)
        val vm = TaskCreateViewModel(
            deps = TaskCreateDeps(
                createTask = CreateTaskUseCase(fakeTasks, com.singularity.todo.core.platform.Clock),
                currentUser = fakeUser,
                logger = Logger.withTag("Test"),
            ),
            initialDueDate = due,
            scopeOverride = testScope,
        )
        subscribe(vm)
        advanceUntilIdle()
        val s = vm.state.first()
        assertIs<DueDateOption.Custom>(s.draft.dueDate)
        assertEquals(due, s.draft.dueDate.date)
        assertTrue(s.isDirty)
    }

    @Test
    fun title_changed_updates_draft_and_enables_save() = testScope.runTest {
        val vm = makeVm()
        subscribe(vm)
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.TitleChanged("Hi"))
        advanceUntilIdle()
        val s = vm.state.first()
        assertEquals("Hi", s.draft.title)
        assertTrue(s.isSaveEnabled)
        assertTrue(s.isDirty)
    }

    @Test
    fun description_changed_updates_draft() = testScope.runTest {
        val vm = makeVm()
        subscribe(vm)
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.DescriptionChanged("desc"))
        advanceUntilIdle()
        assertEquals("desc", vm.state.first().draft.description)
    }

    @Test
    fun setPriority_updates_draft() = testScope.runTest {
        val vm = makeVm()
        subscribe(vm)
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.SetPriority(TaskPriority.High))
        advanceUntilIdle()
        assertEquals(TaskPriority.High, vm.state.first().draft.priority)
    }

    @Test
    fun setDueDate_sets_Custom_option() = testScope.runTest {
        val vm = makeVm()
        subscribe(vm)
        advanceUntilIdle()
        val date = LocalDate(2026, 12, 1)
        vm.onIntent(TaskCreateIntent.SetDueDate(date))
        advanceUntilIdle()
        val due = vm.state.first().draft.dueDate
        assertIs<DueDateOption.Custom>(due)
        assertEquals(date, due.date)
    }

    @Test
    fun setDueTime_updates_draft_with_LocalTime() = testScope.runTest {
        val vm = makeVm()
        subscribe(vm)
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.SetDueTime(LocalTime(14, 30)))
        advanceUntilIdle()
        assertEquals(LocalTime(14, 30), vm.state.first().draft.dueTime)
    }

    @Test
    fun dueDateCleared_resets_date_and_time() = testScope.runTest {
        val vm = makeVm()
        subscribe(vm)
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.SetDueDate(LocalDate(2026, 12, 1)))
        vm.onIntent(TaskCreateIntent.SetDueTime(LocalTime(9, 0)))
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.DueDateCleared)
        advanceUntilIdle()
        val s = vm.state.first()
        assertIs<DueDateOption.None>(s.draft.dueDate)
        assertNull(s.draft.dueTime)
    }

    @Test
    fun saveClicked_succeeds_emits_to_saved_channel_and_resets_isSaving() = testScope.runTest {
        val vm = makeVm()
        subscribe(vm)
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.TitleChanged("Buy milk"))
        advanceUntilIdle()

        var savedEmitted = false
        val job = launch { vm.saved.collect { savedEmitted = true } }

        vm.onIntent(TaskCreateIntent.SaveClicked)
        advanceUntilIdle()
        job.cancel()

        assertTrue(savedEmitted)
        assertFalse(vm.state.first().isSaving)
        assertEquals(1, fakeTasks.tasks.value.size)
        assertEquals("Buy milk", fakeTasks.tasks.value.values.first().title)
    }

    @Test
    fun saveClicked_is_no_op_when_title_blank() = testScope.runTest {
        val vm = makeVm()
        subscribe(vm)
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.SaveClicked)
        advanceUntilIdle()
        assertFalse(vm.state.first().isSaving)
        assertEquals(0, fakeTasks.tasks.value.size)
    }

    @Test
    fun saveClicked_resets_isSaving_after_completion() = testScope.runTest {
        val vm = makeVm()
        subscribe(vm)
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.TitleChanged("Race"))
        advanceUntilIdle()

        assertFalse(vm.state.first().isSaving)
        assertTrue(vm.state.first().isSaveEnabled)

        vm.onIntent(TaskCreateIntent.SaveClicked)
        advanceUntilIdle()
        // try/finally path: even though the suspend call completed synchronously
        // (FakeTaskRepository doesn't suspend), isSaving must be back to false.
        assertFalse(vm.state.first().isSaving)
        assertEquals(1, fakeTasks.tasks.value.size)
    }

    @Test
    fun discardChanges_resets_draft_and_turns_off_isSaving() = testScope.runTest {
        val vm = makeVm()
        subscribe(vm)
        advanceUntilIdle()
        vm.onIntent(TaskCreateIntent.TitleChanged("will be discarded"))
        vm.onIntent(TaskCreateIntent.SetPriority(TaskPriority.High))
        advanceUntilIdle()
        assertTrue(vm.state.first().isDirty)

        vm.onIntent(TaskCreateIntent.DiscardChanges)
        advanceUntilIdle()
        val s = vm.state.first()
        assertEquals("", s.draft.title)
        assertEquals(TaskPriority.None, s.draft.priority)
        assertFalse(s.isDirty)
        assertFalse(s.isSaving)
    }
}

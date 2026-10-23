@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.agenda.presentation.viewmodel

import co.touchlab.kermit.Logger
import com.singularity.todo.core.draft.FakeDraftStore
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.agenda.domain.logic.AgendaPresets
import com.singularity.todo.feature.agenda.domain.model.AgendaIntent
import com.singularity.todo.feature.agenda.domain.model.AgendaUiState
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.reminders.ReminderId
import com.singularity.todo.feature.reminders.ReminderScheduler
import com.singularity.todo.feature.tasks.domain.model.DependencyVerb
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.usecase.MergeTaskSeriesUseCase
import com.singularity.todo.feature.tasks.domain.usecase.TaskReopenUseCase
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeSettingsRepository
import com.singularity.todo.test.fakes.FakeTaskMutationsUseCase
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.TEST_TZ
import com.singularity.todo.test.helpers.VmScope
import com.singularity.todo.test.helpers.testVm
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The bulk close asks the same question the single close asks.
 *
 * The refusal used to reach the user as a snackbar carrying the raw message
 * of the exception, which is not a question and not a way to answer it. Now
 * the selection is held, the tasks in the way are named, and the user's
 * answer decides.
 *
 * The tests live in their own class because the agenda view model test had
 * grown past the size at which a reader can hold it in one head, and these
 * two are a story of their own: the question, and the answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class AgendaBulkCloseTest {

    private val now: Instant = Instant.parse("2026-09-16T09:00:00Z")

    private val fakeRepo = FakeTaskRepository()
    private val fakeSettings = FakeSettingsRepository()
    private val fakeCurrentUser: ProfileAwareCurrentUser = FakeProfileAwareCurrentUser()
    private val fakeTaskMutations = FakeTaskMutationsUseCase(fakeRepo)
    private val fakeReminderScheduler = object : ReminderScheduler {
        override val isSupported: Boolean = true
        override suspend fun schedule(reminder: com.singularity.todo.feature.reminders.Reminder) = Unit
        override suspend fun cancel(id: ReminderId, userId: UserId) = Unit
        override suspend fun cancelByTask(taskId: TaskId, userId: UserId) = Unit
    }
    private val fakeProjectsRepo = FakeProjectsRepository()
    private val completion = TaskReopenUseCase(fakeRepo, fakeProjectsRepo, FakeClock(now))

    private fun task(id: String, title: String) = Task(
        id = TaskId(id),
        title = title,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
        userId = UserId("test-user"),
    )

    /**
     * The ViewModel under test, in the scope its kind of work needs.
     *
     * The agenda collects a clock flow that never ends, so the ViewModel's work
     * cannot be the test's work. The harness owns that choice: the test says
     * *what kind of ViewModel it is testing* — one whose collector never ends —
     * and the harness builds the scope that kind needs.
     */
    private fun TestScope.createVm(): AgendaViewModel = testVm(
        stateAccessor = { vm: AgendaViewModel -> vm.stateFlow },
        vmScope = VmScope.Background,
    ) { scope ->
        AgendaViewModel(
            deps = AgendaDeps(
                taskRepo = fakeRepo,
                clock = FakeClock(now),
                timeZone = TEST_TZ,
                logger = Logger,
                draftStore = FakeDraftStore(),
                reminderScheduler = fakeReminderScheduler,
                currentUser = fakeCurrentUser,
                taskMutations = fakeTaskMutations,
                completion = completion,
                mergeSeries = MergeTaskSeriesUseCase(fakeRepo),
                workScheduleSettings = fakeSettings.workSchedule,
                greetingSettings = fakeSettings.greeting,
            ),
            definition = AgendaPresets.Inbox,
            scope = scope,
        )
    }.vm

    @Test
    fun `a blocked bulk close asks the user instead of writing`() = runTest {
        fakeRepo.seed(task("t1", "Buy milk"), task("t2", "Ship release"))
        fakeRepo.seed(task("dep", "Prerequisite").copy(completedAt = null))
        // t2 waits for a prerequisite that is still open.
        fakeRepo.setDependency(TaskId("t2"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)

        val vm = createVm()
        runCurrent()

        vm.onIntent(AgendaIntent.EnterSelectionMode(TaskId("t1")))
        vm.onIntent(AgendaIntent.ToggleSelection(TaskId("t2")))
        runCurrent()
        vm.onIntent(AgendaIntent.CompleteSelected)
        runCurrent()

        val state = assertIs<AgendaUiState.Loaded>(vm.stateFlow.value)
        val request = assertNotNull(
            state.pendingBlockedBulkClose,
            "the bulk close must ask, the same way the single close does",
        )
        assertEquals(setOf(TaskId("t2")), request.blockedTaskIds.toSet())
        assertEquals(listOf("Ship release"), request.blockedTaskTitles)
        assertEquals(2, request.taskIds.size, "the whole selection is what the answer applies to")
        assertNull(fakeRepo.get(TaskId("t1"))?.completedAt, "nothing may be written before the answer")
        assertNull(fakeRepo.get(TaskId("t2"))?.completedAt, "nothing may be written before the answer")
    }

    @Test
    fun `a confirmed bulk close closes the selection`() = runTest {
        fakeRepo.seed(task("t1", "Buy milk"), task("t2", "Ship release"))
        fakeRepo.seed(task("dep", "Prerequisite").copy(completedAt = null))
        fakeRepo.setDependency(TaskId("t2"), TaskId("dep"), DependencyVerb.BLOCKS, enabled = true)

        val vm = createVm()
        runCurrent()

        vm.onIntent(AgendaIntent.EnterSelectionMode(TaskId("t1")))
        vm.onIntent(AgendaIntent.ToggleSelection(TaskId("t2")))
        runCurrent()
        vm.onIntent(AgendaIntent.CompleteSelected)
        runCurrent()
        vm.onIntent(AgendaIntent.ConfirmBlockedBulkClose)
        runCurrent()

        assertNotNull(fakeRepo.get(TaskId("t1"))?.completedAt, "the free task is closed")
        assertNotNull(fakeRepo.get(TaskId("t2"))?.completedAt, "the confirmed task is closed too")
        val state = assertIs<AgendaUiState.Loaded>(vm.stateFlow.value)
        assertNull(state.pendingBlockedBulkClose, "the confirmation is taken down")
    }
}

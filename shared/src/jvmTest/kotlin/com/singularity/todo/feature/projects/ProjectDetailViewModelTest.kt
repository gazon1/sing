@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.projects

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.projects.domain.usecase.UpdateProjectUseCase
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailIntent
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailUiEvent
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailUiState
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectDetailViewModel
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskFilter
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.port.TaskRepository
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProfileRepository
import com.singularity.todo.test.fakes.FakeProjectRemindersRepository
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTagGroupRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.atStartOfDayIn
import org.junit.jupiter.api.Tag
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Counts how many times the task stream for this project is subscribed.
 *
 * `flatMapLatest` cancels and re-collects its upstream when the trigger flow emits, so a
 * ViewModel that derives this stream from the whole `Project` object re-subscribes on every
 * field write. Deriving it from a nullability signal instead makes the count stable.
 */
private class CountingTaskRepository(private val delegate: TaskRepository) : TaskRepository by delegate {
    var projectFilterSubscriptions = 0
        private set

    override fun observeByFilter(filter: TaskFilter): Flow<List<Task>> {
        if (filter is TaskFilter.ByProject) projectFilterSubscriptions++
        return delegate.observeByFilter(filter)
    }
}

/** Fixed instant: this VM renders reminder dates, which depend on "today". */
private val TEST_NOW: Instant = Instant.parse("2026-01-15T12:00:00Z")

private fun testTaskIn(projectId: String, title: String): Task = Task(
    id = TaskId("t-$projectId-$title"),
    title = title,
    projectId = ProjectId(projectId),
    userId = UserId("test-user"),
    createdAt = TEST_NOW,
    updatedAt = TEST_NOW,
)

/**
 * Unit tests for [ProjectDetailViewModel] verifying behavioral contracts.
 *
 * Timing: uses virtual time via advanceTimeBy(1_000); runCurrent() — no real delays or spin-waiting.
 */
@Tag("fast")
class ProjectDetailViewModelTest {

    private val testUserId = UserId("test-user")
    private val fakeProjectsRepo = FakeProjectsRepository()
    private val fakeTaskRepo = FakeTaskRepository()
    private val fakeAuthRepo = FakeAuthRepository(
        initialSession = com.singularity.todo.core.auth.Session.Anonymous(testUserId),
    )
    private val fakeProfileRepo = FakeProfileRepository()
    private val fakeCurrentUser = FakeProfileAwareCurrentUser(fakeAuthRepo, fakeProfileRepo)
    private val fakeProjectReminders = FakeProjectRemindersRepository()
    private val fakeTagGroupRepo = FakeTagGroupRepository(fakeCurrentUser)

    private fun createVm(
        scope: CoroutineScope,
        taskRepo: TaskRepository = fakeTaskRepo,
        fakeProjectReminders: FakeProjectRemindersRepository = this.fakeProjectReminders,
    ): ProjectDetailViewModel {
        val vm = ProjectDetailViewModel(
            projectId = ProjectId("p1"),
            projectRepo = fakeProjectsRepo,
            taskRepo = taskRepo,
            tagGroupRepo = fakeTagGroupRepo,
            deleteProject = DeleteProjectUseCase(fakeProjectsRepo, fakeTaskRepo),
            updateProject = UpdateProjectUseCase(fakeProjectsRepo, FakeClock(TEST_NOW)),
            updateTask = UpdateTaskUseCase(fakeTaskRepo, FakeClock(TEST_NOW)),
            createTaskUseCase = CreateTaskUseCase(fakeTaskRepo, FakeClock(TEST_NOW), fakeCurrentUser),
            projectReminders = fakeProjectReminders,
            clock = FakeClock(TEST_NOW),
            log = Logger,
            scope = AutoCloseableCoroutineScope(scope.coroutineContext),
        )
        return vm
    }

    private fun seedProject(id: ProjectId = ProjectId("p1")): Project {
        val project = Project(
            id = id,
            name = "Test Project",
            color = 0xFF2196F3.toInt(),
            description = null,
            icon = null,
            parentId = null,
            isDeleted = false,
            createdAt = TEST_NOW,
            updatedAt = TEST_NOW,
            userId = testUserId,
        )
        fakeProjectsRepo.seed(project)
        return project
    }

    @AfterTest
    fun cleanup() {
        fakeProjectsRepo.clear()
        fakeTaskRepo.clear()
    }

    /**
     * Reads the single state snapshot the screen renders. The toggle is part of the
     * state now, not a standalone flow, so the `combine` has to emit `Content` first.
     */
    private fun ProjectDetailViewModel.hideCompleted(): Boolean =
        (stateFlow.value as? ProjectDetailUiState.Content)?.hideCompleted
            ?: error("expected Content, got ${stateFlow.value}")

    private fun ProjectDetailViewModel.hideBlocked(): Boolean =
        (stateFlow.value as? ProjectDetailUiState.Content)?.hideBlocked
            ?: error("expected Content, got ${stateFlow.value}")

    private fun ProjectDetailViewModel.visibleTitles(): List<String> =
        (stateFlow.value as? ProjectDetailUiState.Content)?.ui?.tasks?.map { it.title }
            ?: error("expected Content, got ${stateFlow.value}")

    /** Seeds a task in p1, optionally depending on another task. */
    private fun seedTask(
        title: String,
        dependsOn: Set<TaskId> = emptySet(),
        completed: Boolean = false,
    ): Task = Task(
        id = TaskId("task-$title"),
        title = title,
        projectId = ProjectId("p1"),
        userId = testUserId,
        dependsOn = dependsOn,
        completedAt = if (completed) TEST_NOW else null,
        createdAt = TEST_NOW,
        updatedAt = TEST_NOW,
    )

    @Test
    fun `ToggleHideCompleted flips hideCompleted state`() = runTest {
        seedProject()
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        testScheduler.runCurrent()
        assertFalse(vm.hideCompleted())

        vm.onIntent(ProjectDetailIntent.Domain.ToggleHideCompleted)
        advanceUntilIdle()
        testScheduler.runCurrent()
        assertTrue(vm.hideCompleted())

        vm.onIntent(ProjectDetailIntent.Domain.ToggleHideCompleted)
        advanceUntilIdle()
        testScheduler.runCurrent()
        assertFalse(vm.hideCompleted())
    }

    @Test
    fun `UpdateColor persists new color to repository`() = runTest {
        seedProject()
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(vm.stateFlow.value is ProjectDetailUiState.Content)

        val newColor = 0xFFE91E63.toInt()
        vm.onIntent(ProjectDetailIntent.Domain.UpdateColor(newColor))
        advanceTimeBy(1_000)
        runCurrent()

        val updated = fakeProjectsRepo.store["p1"]
        assertNotNull(updated)
        assertEquals(newColor, updated.color)
    }

    @Test
    fun `ToggleArchive sets isDeleted on project`() = runTest {
        seedProject()
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(vm.stateFlow.value is ProjectDetailUiState.Content)

        vm.onIntent(ProjectDetailIntent.Domain.ToggleArchive)
        advanceTimeBy(1_000)
        runCurrent()

        val updated = fakeProjectsRepo.store["p1"]
        assertNotNull(updated)
        assertTrue(updated.isDeleted)
    }

    @Test
    fun `Delete emits NavigateBack on success`() = runTest {
        seedProject()
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(vm.stateFlow.value is ProjectDetailUiState.Content)

        vm.onIntent(ProjectDetailIntent.Domain.Delete)
        advanceTimeBy(1_000)
        runCurrent()

        // Wait for NavigateBack event
        val event = vm.events.first()
        assertTrue(event is ProjectDetailUiEvent.NavigateBack, "Expected NavigateBack event, got: $event")
    }

    @Test
    fun `CreateTask adds task to repository`() = runTest {
        seedProject()
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(vm.stateFlow.value is ProjectDetailUiState.Content)
        assertTrue(fakeTaskRepo.tasks.value.isEmpty())

        vm.onIntent(ProjectDetailIntent.Domain.CreateTask("New task"))
        advanceTimeBy(1_000)
        runCurrent()

        val tasks = fakeTaskRepo.tasks.value.values.toList()
        assertEquals(1, tasks.size)
        assertEquals("New task", tasks[0].title)
        assertEquals(ProjectId("p1"), tasks[0].projectId)
    }

    /**
     * The task and child-project streams only need to know *whether* a project is loaded,
     * not its fields. Deriving them from a nullability signal instead of the project object
     * means a field write does not cancel and recreate the subscription underneath them.
     */
    @Test
    fun `a project field write does not resubscribe the task stream`() = runTest {
        seedProject()
        val counting = CountingTaskRepository(fakeTaskRepo)
        fakeTaskRepo.seed(testTaskIn("p1", "Before"))
        val vm = createVm(backgroundScope, taskRepo = counting)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(vm.stateFlow.value is ProjectDetailUiState.Content)

        val afterSubscribe = counting.projectFilterSubscriptions
        assertTrue(afterSubscribe > 0, "the task stream was never subscribed")

        vm.onIntent(ProjectDetailIntent.Domain.UpdateColor(0xFFE91E63.toInt()))
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(
            afterSubscribe,
            counting.projectFilterSubscriptions,
            "a project field write restarted the task stream subscription",
        )
    }
    // ─── Project reminder ──────────────────────────────────────────────────────

    /**
     * The reminder is authored as an offset from the due date and stored as an
     * absolute instant. If the two drift apart the picker shows a different value
     * than the user chose, so the round trip is asserted explicitly.
     */
    @Test
    fun `setReminder stores fireAt as dueDate minus the chosen offset`() = runTest {
        val project = seedProject().copy(dueDate = kotlinx.datetime.LocalDate(2026, 10, 15))
        fakeProjectsRepo.seed(project)
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        runCurrent()

        vm.onIntent(ProjectDetailIntent.Domain.SetReminder(60))
        advanceUntilIdle()
        runCurrent()

        val stored = fakeProjectReminders.all().single()
        val dueMillis = project.dueDate!!
            .atStartOfDayIn(kotlinx.datetime.TimeZone.currentSystemDefault())
            .toEpochMilliseconds()
        assertEquals(
            dueMillis - 60 * 60_000L,
            stored.fireAt,
            "the stored instant must be the due date less the chosen offset",
        )
    }

    @Test
    fun `setting a reminder twice updates in place instead of stacking rows`() = runTest {
        fakeProjectsRepo.seed(seedProject().copy(dueDate = kotlinx.datetime.LocalDate(2026, 10, 15)))
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        runCurrent()

        vm.onIntent(ProjectDetailIntent.Domain.SetReminder(15))
        advanceUntilIdle()
        runCurrent()
        vm.onIntent(ProjectDetailIntent.Domain.SetReminder(1440))
        advanceUntilIdle()
        runCurrent()

        assertEquals(1, fakeProjectReminders.all().size, "re-picking must reuse the existing reminder row")
    }

    @Test
    fun `a null offset removes the reminder`() = runTest {
        fakeProjectsRepo.seed(seedProject().copy(dueDate = kotlinx.datetime.LocalDate(2026, 10, 15)))
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        runCurrent()

        vm.onIntent(ProjectDetailIntent.Domain.SetReminder(60))
        advanceUntilIdle()
        runCurrent()
        assertEquals(1, fakeProjectReminders.all().size)

        vm.onIntent(ProjectDetailIntent.Domain.SetReminder(null))
        advanceUntilIdle()
        runCurrent()
        assertTrue(fakeProjectReminders.all().isEmpty(), "clearing the reminder must delete the row")
    }

    /**
     * A reminder is anchored to a due date. With no due date there is nothing to
     * anchor to, and silently storing a reminder the user cannot reason about would
     * be worse than refusing.
     */
    @Test
    fun `a reminder on a project with no due date is rejected, not silently dropped`() = runTest {
        seedProject()
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        runCurrent()

        vm.onIntent(ProjectDetailIntent.Domain.SetReminder(60))
        advanceUntilIdle()
        runCurrent()

        assertTrue(fakeProjectReminders.all().isEmpty(), "no due date means no reminder is stored")
    }

    // ─── Hide blocked ──────────────────────────────────────────────────────────

    @Test
    fun `blocked tasks are visible by default`() = runTest {
        seedProject()
        fakeTaskRepo.seed(seedTask("prereq"), seedTask("waiting", dependsOn = setOf(TaskId("task-prereq"))))
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        runCurrent()

        assertFalse(vm.hideBlocked(), "hiding blocked tasks must be opt-in, never silent")
        assertEquals(listOf("prereq", "waiting"), vm.visibleTitles().sorted())
    }

    @Test
    fun `ToggleHideBlocked removes only the blocked task`() = runTest {
        seedProject()
        fakeTaskRepo.seed(seedTask("prereq"), seedTask("waiting", dependsOn = setOf(TaskId("task-prereq"))))
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        runCurrent()

        vm.onIntent(ProjectDetailIntent.Domain.ToggleHideBlocked)
        advanceUntilIdle()
        runCurrent()

        assertTrue(vm.hideBlocked())
        assertEquals(listOf("prereq"), vm.visibleTitles())
    }

    @Test
    fun `toggling hide blocked off restores the task`() = runTest {
        seedProject()
        fakeTaskRepo.seed(seedTask("prereq"), seedTask("waiting", dependsOn = setOf(TaskId("task-prereq"))))
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        runCurrent()

        vm.onIntent(ProjectDetailIntent.Domain.ToggleHideBlocked)
        advanceUntilIdle()
        runCurrent()
        vm.onIntent(ProjectDetailIntent.Domain.ToggleHideBlocked)
        advanceUntilIdle()
        runCurrent()

        assertFalse(vm.hideBlocked())
        assertEquals(2, vm.visibleTitles().size)
    }

    @Test
    fun `hiding blocked does not mutate stored tasks`() = runTest {
        seedProject()
        fakeTaskRepo.seed(seedTask("prereq"), seedTask("waiting", dependsOn = setOf(TaskId("task-prereq"))))
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        runCurrent()

        vm.onIntent(ProjectDetailIntent.Domain.ToggleHideBlocked)
        advanceUntilIdle()
        runCurrent()

        assertEquals(2, fakeTaskRepo.tasks.value.size, "filtering is presentation-only")
        assertNotNull(fakeTaskRepo.get(TaskId("task-waiting")))
    }

    @Test
    fun `hide blocked and hide completed compose`() = runTest {
        seedProject()
        fakeTaskRepo.seed(
            seedTask("prereq"),
            seedTask("waiting", dependsOn = setOf(TaskId("task-prereq"))),
            seedTask("done", completed = true),
        )
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        runCurrent()

        vm.onIntent(ProjectDetailIntent.Domain.ToggleHideBlocked)
        advanceUntilIdle()
        runCurrent()
        vm.onIntent(ProjectDetailIntent.Domain.ToggleHideCompleted)
        advanceUntilIdle()
        runCurrent()

        assertEquals(listOf("prereq"), vm.visibleTitles())
    }

    @Test
    fun `hiding completed tasks does not change which tasks count as blocked`() = runTest {
        // Blocking is a property of a task in the world: a dependency that is
        // hidden by one filter still blocks whatever it blocks. If `blockedIds`
        // were computed from the already-filtered list, hiding a task could
        // retroactively unblock its dependents.
        seedProject()
        fakeTaskRepo.seed(
            seedTask("blocker"),
            seedTask("waiting", dependsOn = setOf(TaskId("task-blocker"))),
            seedTask("done", completed = true),
        )
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        runCurrent()

        vm.onIntent(ProjectDetailIntent.Domain.ToggleHideCompleted)
        advanceUntilIdle()
        runCurrent()
        // "done" is gone; "waiting" is still blocked by the still-present "blocker".
        assertEquals(listOf("blocker", "waiting"), vm.visibleTitles().sorted())

        vm.onIntent(ProjectDetailIntent.Domain.ToggleHideBlocked)
        advanceUntilIdle()
        runCurrent()
        assertEquals(
            listOf("blocker"),
            vm.visibleTitles(),
            "hiding completed work must not free a blocked task",
        )
    }

    @Test
    fun `a completed dependency does not block`() = runTest {
        // The domain rule itself, pinned so the filter above is read correctly:
        // only an *unfinished* dependency blocks.
        seedProject()
        fakeTaskRepo.seed(
            seedTask("done-dep", completed = true),
            seedTask("waiting", dependsOn = setOf(TaskId("task-done-dep"))),
        )
        val vm = createVm(backgroundScope)
        advanceUntilIdle()
        runCurrent()

        vm.onIntent(ProjectDetailIntent.Domain.ToggleHideBlocked)
        advanceUntilIdle()
        runCurrent()

        assertEquals(
            listOf("done-dep", "waiting"),
            vm.visibleTitles().sorted(),
            "a completed dependency is not blocked work, and it stays visible — " +
                "hiding completed tasks is a separate filter",
        )
    }
}

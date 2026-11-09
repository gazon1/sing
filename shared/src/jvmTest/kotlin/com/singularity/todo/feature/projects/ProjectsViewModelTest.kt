
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.projects

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.database.ProjectWithCountRow
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.projects.domain.state.ProjectSortOrder
import com.singularity.todo.feature.projects.domain.state.ProjectsUiState
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsIntent
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsViewModel
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.RecordingCrashReportingPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Tests for [ProjectsViewModel].
 *
 * Uses [SharingStarted.Eagerly] so the state flow starts immediately.
 * VM collectors run as direct backgroundScope children; tests pump the virtual
 * clock with [advanceTimeBy] because background tasks only execute while the
 * test body is suspended or time advances (advanceUntilIdle does not run them).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class ProjectsViewModelTest {
    private val testUserId = UserId("test-user")
    private val fakeProjectRepo = FakeProjectsRepository()
    private val fakeTaskRepo = FakeTaskRepository()

    private fun TestScope.createVm(): ProjectsViewModel =
        ProjectsViewModel(
            projectRepo = fakeProjectRepo,
            taskRepository = fakeTaskRepo,
            deleteProject = DeleteProjectUseCase(fakeProjectRepo, fakeTaskRepo),
            scope = AutoCloseableCoroutineScope(backgroundScope.coroutineContext),
        )

    private fun TestScope.seedProject(
        id: String,
        name: String,
        color: Int = 0xFF2196F3.toInt(),
        icon: String? = null,
        parentId: ProjectId? = null,
    ) {
        val now = Clock.System.now()
        fakeProjectRepo.seed(
            Project(
                id = ProjectId.fromString(id),
                name = name,
                color = color,
                icon = icon,
                parentId = parentId,
                createdAt = now,
                updatedAt = now,
                userId = testUserId,
            ),
        )
    }

    // ─── initial state ─────────────────────────────────────────────────────

    @Test
    fun `initial state is Loading`() =
        runTest {
            val vm = createVm()
            assertIs<ProjectsUiState.Loading>(vm.stateFlow.value)
        }

    // ─── search ────────────────────────────────────────────────────────────

    @Test
    fun `searchQuery state updates immediately`() =
        runTest {
            // seed a project matching the query below: with an empty/filtered-out repo
            // the VM emits Empty, which carries no searchQuery field
            seedProject("p1", "Work Project")
            val vm = createVm()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("", (vm.stateFlow.value as? ProjectsUiState.Content)?.searchQuery?.text)

            vm.onIntent(ProjectsIntent.SetSearchQuery("Work"))
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("Work", (vm.stateFlow.value as? ProjectsUiState.Content)?.searchQuery?.text)

            vm.onIntent(ProjectsIntent.SetSearchQuery(""))
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("", (vm.stateFlow.value as? ProjectsUiState.Content)?.searchQuery?.text)
        }

    @Test
    fun `sortOrder state updates immediately`() =
        runTest {
            val vm = createVm()
            seedProject("p1", "Project")
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(ProjectSortOrder.Name, (vm.stateFlow.value as? ProjectsUiState.Content)?.sortOrder)

            vm.onIntent(ProjectsIntent.SetSortOrder(ProjectSortOrder.Color))
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(ProjectSortOrder.Color, (vm.stateFlow.value as? ProjectsUiState.Content)?.sortOrder)

            vm.onIntent(ProjectsIntent.SetSortOrder(ProjectSortOrder.Name))
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(ProjectSortOrder.Name, (vm.stateFlow.value as? ProjectsUiState.Content)?.sortOrder)
        }

    @Test
    fun `an error keeps the query and the sort order`() =
        runTest {
            // The screen binds the search field and the sort control to the state, so a
            // failure that reset them would clear what the user had typed: the field is
            // bound to the query, so the reset would reach the ViewModel as if the user
            // had cleared it, and the list could never come back.
            //
            // The repository fails on the *second* subscription, not the first: the list
            // has to load first, or there is nothing for the user to have chosen and the
            // error would arrive before the query is ever set.
            val vm =
                ProjectsViewModel(
                    projectRepo = FailingProjectsRepository(fakeProjectRepo),
                    taskRepository = fakeTaskRepo,
                    deleteProject = DeleteProjectUseCase(fakeProjectRepo, fakeTaskRepo),
                    scope = AutoCloseableCoroutineScope(backgroundScope.coroutineContext),
                )
            advanceTimeBy(1_000)
            runCurrent()

            vm.onIntent(ProjectsIntent.SetSearchQuery("Work"))
            vm.onIntent(ProjectsIntent.SetSortOrder(ProjectSortOrder.Color))
            advanceTimeBy(1_000)
            runCurrent()

            val error = assertIs<ProjectsUiState.Error>(vm.stateFlow.value)
            assertEquals("Work", error.searchQuery.text)
            assertEquals(ProjectSortOrder.Color, error.sortOrder)
        }

    // ─── the closed projects ──────────────────────────────────────────────

    @Test
    fun `a closed project leaves the list and comes back on its own list`() =
        runTest {
            seedProject("p1", "Open project")
            seedProject("p2", "Closed project")
            val vm = createVm()
            advanceTimeBy(1_000)
            runCurrent()

            fakeProjectRepo.close(ProjectId.fromString("p2"), 1_000L).getOrThrow()
            advanceTimeBy(1_000)
            runCurrent()

            val state = assertIs<ProjectsUiState.Content>(vm.stateFlow.value)
            assertEquals(
                listOf("Open project"),
                state.projects.map { it.project.name },
                "a closed project leaves the project list",
            )
            assertEquals(
                listOf("Closed project"),
                state.closedProjects.map { it.name },
                "and it is still reachable, with the way back",
            )
        }

    @Test
    fun `opening a closed project puts it back in the list`() =
        runTest {
            seedProject("p2", "Closed project")
            val vm = createVm()
            advanceTimeBy(1_000)
            runCurrent()

            fakeProjectRepo.close(ProjectId.fromString("p2"), 1_000L).getOrThrow()
            advanceTimeBy(1_000)
            runCurrent()

            vm.onIntent(ProjectsIntent.Reopen(ProjectId.fromString("p2")))
            advanceTimeBy(1_000)
            runCurrent()

            val state = assertIs<ProjectsUiState.Content>(vm.stateFlow.value)
            assertEquals(
                listOf("Closed project"),
                state.projects.map { it.project.name },
                "the project is back in the list",
            )
            assertTrue(state.closedProjects.isEmpty(), "and it is no longer a closed one")
        }

    // ─── delete (via use-case, not state) ──────────────────────────────────

    @Test
    fun `delete soft-deletes project via use-case`() =
        runTest {
            seedProject("p1", "To Delete")
            val vm = createVm()
            advanceTimeBy(1_000)
            runCurrent()

            // Verify store has the project
            val before = fakeProjectRepo.store.values().filter { !it.isDeleted }
            assertEquals(1, before.size, "Store should have the seeded project")

            vm.onIntent(ProjectsIntent.Delete(ProjectId.fromString("p1")))
            advanceTimeBy(1_000)
            runCurrent()

            // Verify store reflects soft-delete
            val after = fakeProjectRepo.store.values().filter { !it.isDeleted }
            assertEquals(0, after.size, "After soft-delete, no non-deleted projects should remain in store")
        }

    /**
     * A Retry after a failed load must observe the projects again.
     *
     * The Retry intent used to run a private `reload()` whose `catch` sat on the
     * observation's own chain: the failed collection ended with the exception it
     * handled, so the second attempt restarted nothing and the screen stayed on
     * the error state. The observable that tells the two apart is the repository
     * being asked a second time — a retry that only rewrote the state would pass
     * a test that only looked at the state.
     */
    @Test
    fun `retry after a failed load observes the projects again`() =
        runTest {
            seedProject("p1", "Work")
            fakeProjectRepo.nextObserveCountsFailure = IllegalStateException("the observation failed")

            val vm = createVm()
            advanceTimeBy(1_000)
            runCurrent()

            assertIs<ProjectsUiState.Error>(vm.stateFlow.value)
            assertEquals(1, fakeProjectRepo.observeProjectsWithCountsCalls)

            vm.onIntent(ProjectsIntent.Retry)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(
                2,
                fakeProjectRepo.observeProjectsWithCountsCalls,
                "Retry must observe the projects again, not only change the state",
            )
            assertIs<ProjectsUiState.Content>(
                vm.stateFlow.value,
                "the retry must leave the error state and show the list again",
            )
        }

    /**
     * A retry that fails again lands in the error state, not on a spinner.
     *
     * The state Retry writes is a promise that a load is running; when that load
     * fails the error state has to replace it, or the screen waits for ever.
     */
    @Test
    fun `a retry that fails again lands in the error state`() =
        runTest {
            seedProject("p1", "Work")
            fakeProjectRepo.nextObserveCountsFailure = IllegalStateException("the first attempt failed")

            val vm = createVm()
            advanceTimeBy(1_000)
            runCurrent()

            assertIs<ProjectsUiState.Error>(vm.stateFlow.value)

            fakeProjectRepo.nextObserveCountsFailure = IllegalStateException("the retry failed too")
            vm.onIntent(ProjectsIntent.Retry)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(2, fakeProjectRepo.observeProjectsWithCountsCalls)
            assertIs<ProjectsUiState.Error>(
                vm.stateFlow.value,
                "a failed retry must land in the error state, not on the spinner",
            )
        }

    /**
     * A stopped observation must hand the *cause* to the reporter.
     *
     * The screen can only show a message; the message alone says nothing about
     * why the list stopped, and the throwable that explains it is the thing the
     * reporter exists for. So the report has to carry the original throwable —
     * the very instance the repository threw, not a copy of it — under this
     * screen's own key.
     */
    @Test
    fun `a failed observation reports the original cause`() =
        runTest {
            seedProject("p1", "Work Project")
            val failure = IllegalStateException("the project repository is down")
            val reporter = RecordingCrashReportingPort()
            val vm =
                ProjectsViewModel(
                    projectRepo = FailingProjectsRepository(fakeProjectRepo, failure),
                    taskRepository = fakeTaskRepo,
                    deleteProject = DeleteProjectUseCase(fakeProjectRepo, fakeTaskRepo),
                    crashReporter = reporter,
                    scope = AutoCloseableCoroutineScope(backgroundScope.coroutineContext),
                )
            advanceTimeBy(1_000)
            runCurrent()
            assertIs<ProjectsUiState.Content>(vm.stateFlow.value)

            // The next subscription fails, with the user's choices in force.
            vm.onIntent(ProjectsIntent.SetSearchQuery("Work"))
            advanceTimeBy(1_000)
            runCurrent()

            val error = assertIs<ProjectsUiState.Error>(vm.stateFlow.value)
            assertEquals("Work", error.searchQuery.text, "a failure must not clear the query")

            val reported = reporter.reports.single { it.second == "projects.observe_failed" }
            assertSame(
                failure,
                reported.first,
                "the reporter must receive the throwable the repository threw",
            )
        }
}

/**
 * A projects repository that works until the ViewModel asks a second time.
 *
 * The fake has failure overrides for the *writes*; a failing observation is the other
 * half of the failure surface, and it is the one the list screens show an error for.
 *
 * The first subscription delivers the rows; every later one throws. The ViewModel
 * re-subscribes on each query or sort change, so the failure lands *after* the list
 * has loaded and the user has chosen something — which is the only moment where
 * "the error kept the query" means anything.
 */
private class FailingProjectsRepository(
    private val delegate: ProjectsRepository,
    /**
     * The throwable the failure carries.
     *
     * The report has to carry this exact instance — the one the repository threw —
     * so a test can tell "the cause reached the reporter" from "something was
     * reported about a cause".
     */
    private val failure: Throwable = IllegalStateException("the project repository is down"),
) : ProjectsRepository by delegate {
    private var subscriptions = 0

    override fun observeProjectsWithCounts(): Flow<List<ProjectWithCountRow>> =
        flow {
            subscriptions++
            if (subscriptions == 1) {
                emitAll(delegate.observeProjectsWithCounts())
            } else {
                throw failure
            }
        }
}

package com.singularity.todo.feature.projects

import co.touchlab.kermit.Logger
import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.projects.domain.usecase.UpdateProjectUseCase
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailIntent
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailUiEvent
import com.singularity.todo.feature.projects.presentation.state.ProjectDetailUiState
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectDetailViewModel
import com.singularity.todo.feature.tasks.domain.usecase.CreateTaskUseCase
import com.singularity.todo.feature.tasks.domain.usecase.UpdateTaskUseCase
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProfileRepository
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ProjectDetailViewModel] verifying behavioral contracts.
 *
 * Timing: uses virtual time via advanceTimeBy(1_000); runCurrent() — no real delays or spin-waiting.
 */
class ProjectDetailViewModelTest {

    private val testUserId = UserId("test-user")
    private val fakeProjectsRepo = FakeProjectsRepository()
    private val fakeTaskRepo = FakeTaskRepository()
    private val fakeAuthRepo = FakeAuthRepository(
        initialSession = com.singularity.todo.core.auth.Session.Anonymous(testUserId),
    )
    private val fakeProfileRepo = FakeProfileRepository()
    private val fakeCurrentUser = FakeProfileAwareCurrentUser(fakeAuthRepo, fakeProfileRepo)

    private fun createVm(scope: CoroutineScope): ProjectDetailViewModel {
        val vm = ProjectDetailViewModel(
            projectId = ProjectId("p1"),
            projectRepo = fakeProjectsRepo,
            taskRepo = fakeTaskRepo,
            deleteProject = DeleteProjectUseCase(fakeProjectsRepo, fakeTaskRepo),
            updateProject = UpdateProjectUseCase(fakeProjectsRepo, Clock),
            updateTask = UpdateTaskUseCase(fakeTaskRepo, Clock),
            createTaskUseCase = CreateTaskUseCase(fakeTaskRepo, Clock, fakeCurrentUser),
            clock = Clock,
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
            createdAt = Clock.now(),
            updatedAt = Clock.now(),
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

    @Test
    fun `ToggleHideCompleted flips hideCompleted state`() = runTest {
        seedProject()
        val vm = createVm(backgroundScope)
        assertFalse(vm.hideCompleted.value)

        vm.onIntent(ProjectDetailIntent.Domain.ToggleHideCompleted)
        assertTrue(vm.hideCompleted.value)

        vm.onIntent(ProjectDetailIntent.Domain.ToggleHideCompleted)
        assertFalse(vm.hideCompleted.value)
    }

    @Test
    fun `UpdateColor persists new color to repository`() = runTest {
        seedProject()
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(vm.state.value is ProjectDetailUiState.Content)

        val newColor = 0xFFE91E63.toInt()
        vm.onIntent(ProjectDetailIntent.Domain.UpdateColor(newColor))
        advanceTimeBy(1_000); runCurrent()

        val updated = fakeProjectsRepo.store["p1"]
        assertNotNull(updated)
        assertEquals(newColor, updated.color)
    }

    @Test
    fun `ToggleArchive sets isDeleted on project`() = runTest {
        seedProject()
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(vm.state.value is ProjectDetailUiState.Content)

        vm.onIntent(ProjectDetailIntent.Domain.ToggleArchive)
        advanceTimeBy(1_000); runCurrent()

        val updated = fakeProjectsRepo.store["p1"]
        assertNotNull(updated)
        assertTrue(updated.isDeleted)
    }

    @Test
    fun `Delete emits NavigateBack on success`() = runTest {
        seedProject()
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(vm.state.value is ProjectDetailUiState.Content)

        vm.onIntent(ProjectDetailIntent.Domain.Delete)
        advanceTimeBy(1_000); runCurrent()

        // Wait for NavigateBack event
        val event = vm.events.first()
        assertTrue(event is ProjectDetailUiEvent.NavigateBack, "Expected NavigateBack event, got: $event")
    }

    @Test
    fun `CreateTask adds task to repository`() = runTest {
        seedProject()
        val vm = createVm(backgroundScope)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(vm.state.value is ProjectDetailUiState.Content)
        assertTrue(fakeTaskRepo.tasks.value.isEmpty())

        vm.onIntent(ProjectDetailIntent.Domain.CreateTask("New task"))
        advanceTimeBy(1_000); runCurrent()

        val tasks = fakeTaskRepo.tasks.value.values.toList()
        assertEquals(1, tasks.size)
        assertEquals("New task", tasks[0].title)
        assertEquals(ProjectId("p1"), tasks[0].projectId)
    }
}

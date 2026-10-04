@file:Suppress("NoDirectClockSystem")

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.projects

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.projects.presentation.state.ProjectSortOrder
import com.singularity.todo.feature.projects.presentation.state.ProjectsUiState
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsIntent
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsViewModel
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    private fun TestScope.createVm(): ProjectsViewModel = ProjectsViewModel(
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
    fun `initial state is Loading`() = runTest {
        val vm = createVm()
        assertIs<ProjectsUiState.Loading>(vm.state.value)
    }

    // ─── search ────────────────────────────────────────────────────────────

    @Test
    fun `searchQuery state updates immediately`() = runTest {
        // seed a project matching the query below: with an empty/filtered-out repo
        // the VM emits Empty, which carries no searchQuery field
        seedProject("p1", "Work Project")
        val vm = createVm()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("", (vm.state.value as? ProjectsUiState.Content)?.searchQuery)

        vm.onIntent(ProjectsIntent.SetSearchQuery("Work"))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("Work", (vm.state.value as? ProjectsUiState.Content)?.searchQuery)

        vm.onIntent(ProjectsIntent.SetSearchQuery(""))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("", (vm.state.value as? ProjectsUiState.Content)?.searchQuery)
    }

    @Test
    fun `sortOrder state updates immediately`() = runTest {
        val vm = createVm()
        seedProject("p1", "Project")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ProjectSortOrder.Name, (vm.state.value as? ProjectsUiState.Content)?.sortOrder)

        vm.onIntent(ProjectsIntent.SetSortOrder(ProjectSortOrder.Color))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ProjectSortOrder.Color, (vm.state.value as? ProjectsUiState.Content)?.sortOrder)

        vm.onIntent(ProjectsIntent.SetSortOrder(ProjectSortOrder.Name))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ProjectSortOrder.Name, (vm.state.value as? ProjectsUiState.Content)?.sortOrder)
    }

    // ─── delete (via use-case, not state) ──────────────────────────────────

    @Test
    fun `delete soft-deletes project via use-case`() = runTest {
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
}

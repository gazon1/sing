package com.singularity.todo.feature.projects

import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.projects.presentation.state.ProjectSortOrder
import com.singularity.todo.feature.projects.presentation.state.ProjectsUiState
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectsViewModel
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

/**
 * Tests for [ProjectsViewModel].
 *
 * Uses [SharingStarted.Eagerly] so the state flow starts immediately.
 * [advanceUntilIdle] processes all pending coroutine work on the test dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("slow")
class ProjectsViewModelTest {
    private val testUserId = UserId("test-user")
    private val fakeProjectRepo = FakeProjectsRepository()
    private val fakeTaskRepo = FakeTaskRepository()

    private fun TestScope.createVm(): ProjectsViewModel = ProjectsViewModel(
        projectRepo = fakeProjectRepo,
        taskRepository = fakeTaskRepo,
        deleteProject = DeleteProjectUseCase(fakeProjectRepo, fakeTaskRepo),
        scope = testScope(backgroundScope),
    )

    private fun TestScope.seedProject(
        id: String,
        name: String,
        color: Int = 0xFF2196F3.toInt(),
        icon: String? = null,
        parentId: ProjectId? = null,
    ) {
        val now = Clock.now()
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
        val vm = createVm()
        assertEquals("", vm.searchQuery.value)

        vm.setSearchQuery("Work")
        assertEquals("Work", vm.searchQuery.value)

        vm.setSearchQuery("")
        assertEquals("", vm.searchQuery.value)
    }

    @Test
    fun `sortOrder state updates immediately`() = runTest {
        val vm = createVm()
        assertEquals(ProjectSortOrder.Name, vm.sortOrder.value)

        vm.setSortOrder(ProjectSortOrder.Color)
        assertEquals(ProjectSortOrder.Color, vm.sortOrder.value)

        vm.setSortOrder(ProjectSortOrder.Name)
        assertEquals(ProjectSortOrder.Name, vm.sortOrder.value)
    }

    // ─── delete (via use-case, not state) ──────────────────────────────────

    @Test
    fun `delete soft-deletes project via use-case`() = runTest {
        seedProject("p1", "To Delete")
        val vm = createVm()
        advanceUntilIdle()

        // Verify store has the project
        val before = fakeProjectRepo.store.values().filter { !it.isDeleted }
        assertEquals(1, before.size, "Store should have the seeded project")

        vm.delete(ProjectId.fromString("p1"))
        advanceUntilIdle()

        // Verify store reflects soft-delete
        val after = fakeProjectRepo.store.values().filter { !it.isDeleted }
        assertEquals(0, after.size, "After soft-delete, no non-deleted projects should remain in store")
    }
}

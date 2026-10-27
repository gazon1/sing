@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.projects.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeProjectsTreeSettingsRepository
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The menu's project tree, as the ViewModel that feeds it sees it.
 *
 * The tree is live — the menu is opened and closed many times a day, so a tree
 * that showed what the projects looked like when the app started would be wrong
 * more often than right. The rename is the sharper half of that: a stale tree
 * shows the old name under the new project.
 */
@Tag("fast")
class ProjectsTreeViewModelTest {

    @Test
    fun `the tree follows the projects`() = runTest {
        val repo = FakeProjectsRepository()
        repo.seed(project("root", "Product"), project("child", "Website", parent = "root"))
        val h = newHarness(repo)
        advanceUntilIdle()

        val state = h.vm.stateFlow.value as ProjectsTreeUiState.Loaded
        assertEquals(listOf("root", "child"), state.nodes.map { it.id.value })
        h.close()
    }

    @Test
    fun `a project renamed elsewhere shows its new name`() = runTest {
        val repo = FakeProjectsRepository()
        repo.seed(project("root", "Product"))
        val h = newHarness(repo)
        advanceUntilIdle()

        repo.add(project("root", "Renamed"))
        advanceUntilIdle()

        val state = h.vm.stateFlow.value as ProjectsTreeUiState.Loaded
        assertEquals(listOf("Renamed"), state.nodes.map { it.name })
        h.close()
    }

    @Test
    fun `folding a project away hides its children and keeps them folded`() = runTest {
        val repo = FakeProjectsRepository()
        repo.seed(project("root", "Product"), project("child", "Website", parent = "root"))
        val h = newHarness(repo)
        advanceUntilIdle()

        h.vm.onIntent(ProjectsTreeIntent.ToggleExpanded(ProjectId("root")))
        advanceUntilIdle()

        val state = h.vm.stateFlow.value as ProjectsTreeUiState.Loaded
        assertEquals(listOf("root"), state.nodes.map { it.id.value })

        // A project that arrives later does not unfold what the user folded.
        repo.add(project("other", "Other"))
        advanceUntilIdle()
        assertEquals(
            // The roots are ordered by name, so "Other" comes before "Product".
            listOf("other", "root"),
            (h.vm.stateFlow.value as ProjectsTreeUiState.Loaded).nodes.map { it.id.value },
        )
        h.close()
    }

    @Test
    fun `the folding survives a new tree built over the same settings`() = runTest {
        val repo = FakeProjectsRepository()
        repo.seed(project("root", "Product"), project("child", "Website", parent = "root"))
        val settings = FakeProjectsTreeSettingsRepository()

        val first = newHarness(repo, settings)
        advanceUntilIdle()
        first.vm.onIntent(ProjectsTreeIntent.ToggleExpanded(ProjectId("root")))
        advanceUntilIdle()
        assertEquals(listOf("root"), (first.vm.stateFlow.value as ProjectsTreeUiState.Loaded).nodes.map { it.id.value })
        first.close()

        // The menu is opened again: the tree is built anew, over the same settings.
        val second = newHarness(repo, settings)
        advanceUntilIdle()

        assertEquals(listOf("root"), (second.vm.stateFlow.value as ProjectsTreeUiState.Loaded).nodes.map { it.id.value })
        second.close()
    }

    @Test
    fun `unfolding a project shows its children again`() = runTest {
        val repo = FakeProjectsRepository()
        repo.seed(project("root", "Product"), project("child", "Website", parent = "root"))
        val h = newHarness(repo)
        advanceUntilIdle()

        h.vm.onIntent(ProjectsTreeIntent.ToggleExpanded(ProjectId("root")))
        advanceUntilIdle()
        h.vm.onIntent(ProjectsTreeIntent.ToggleExpanded(ProjectId("root")))
        advanceUntilIdle()

        val state = h.vm.stateFlow.value as ProjectsTreeUiState.Loaded
        assertEquals(listOf("root", "child"), state.nodes.map { it.id.value })
        h.close()
    }

    private fun project(id: String, name: String, parent: String? = null): Project = Project(
        id = ProjectId(id),
        name = name,
        color = 0,
        createdAt = NOW,
        updatedAt = NOW,
        parentId = parent?.let { ProjectId(it) },
        userId = UserId("test-user"),
    )

    private class Harness(val vm: ProjectsTreeViewModel, private val scope: AutoCloseableCoroutineScope) :
        AutoCloseable {
        override fun close() = scope.close()
    }

    private fun TestScope.newHarness(
        repo: FakeProjectsRepository,
        settings: FakeProjectsTreeSettingsRepository = FakeProjectsTreeSettingsRepository(),
    ): Harness {
        val scope = testScope(this)
        return Harness(
            vm = ProjectsTreeViewModel(repo = repo, settings = settings, scope = scope),
            scope = scope,
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-16T10:00:00Z")
    }
}

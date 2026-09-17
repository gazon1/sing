package com.singularity.todo.feature.projects

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.usecase.CreateProjectUseCase
import com.singularity.todo.feature.projects.domain.usecase.UpdateProjectUseCase
import com.singularity.todo.feature.projects.presentation.state.ProjectEditorIntent
import com.singularity.todo.feature.projects.presentation.viewmodel.ProjectEditorViewModel
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Smoke tests for [ProjectEditorViewModel] — verify construction and basic intent flow.
 * Detailed save/load tests are out of scope for MR3.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectEditorViewModelTest {

    private val testUserId = UserId("test-user")
    private val fakeProjectsRepo = FakeProjectsRepository()
    private val fakeCurrentUser = FakeProfileAwareCurrentUser(
        FakeAuthRepository(initialSession = Session.Anonymous(testUserId)),
    )

    private fun createVm(scope: CoroutineScope, projectId: ProjectId? = null) =
        ProjectEditorViewModel(
            projectId = projectId,
            createProject = CreateProjectUseCase(fakeProjectsRepo, Clock),
            updateProject = UpdateProjectUseCase(fakeProjectsRepo, Clock),
            projectsRepo = fakeProjectsRepo,
            currentUser = fakeCurrentUser,
            scope = scope,
        )

    @Test
    fun `initial state has empty name for new project`() = runTest {
        val vm = createVm(backgroundScope)
        assertEquals("", vm.state.value.name)
    }

    @Test
    fun `NameChanged updates state`() = runTest {
        val vm = createVm(backgroundScope)
        vm.processIntent(ProjectEditorIntent.NameChanged("My project"))
        assertEquals("My project", vm.state.value.name)
    }
}

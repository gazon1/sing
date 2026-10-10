
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.projects

import com.singularity.todo.core.auth.Session
import com.singularity.todo.core.coroutines.testScope
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.ProjectsDomain
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Smoke tests for [ProjectEditorViewModel] — verify construction and basic intent flow.
 * Detailed save/load tests are out of scope for MR3.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class ProjectEditorViewModelTest {

    private val testUserId = UserId("test-user")
    private val fakeProjectsRepo = FakeProjectsRepository()
    private val fakeCurrentUser = FakeProfileAwareCurrentUser(
        FakeAuthRepository(initialSession = Session.Anonymous(testUserId)),
    )

    private fun createVm(scope: CoroutineScope, projectId: ProjectId? = null) = ProjectEditorViewModel(
        projectId = projectId,
        createProject = CreateProjectUseCase(fakeProjectsRepo, Clock.System, fakeCurrentUser),
        updateProject = UpdateProjectUseCase(fakeProjectsRepo, Clock.System),
        projectsRepo = fakeProjectsRepo,
        scope = testScope(scope),
    )

    @Test
    fun `initial state has empty name for new project`() = runTest {
        val vm = createVm(backgroundScope)
        assertEquals("", vm.stateFlow.value.name)
    }

    @Test
    fun `NameChanged updates state`() = runTest {
        val vm = createVm(backgroundScope)
        vm.onIntent(ProjectEditorIntent.NameChanged("My project"))
        assertEquals("My project", vm.stateFlow.value.name)
    }

    // ─── W4: the editor must not keep a private copy of the name rule ──────────

    @Test
    fun `save accepts a name of exactly max length`() = runTest {
        val vm = createVm(backgroundScope)
        vm.onIntent(ProjectEditorIntent.NameChanged("a".repeat(ProjectsDomain.MAX_NAME_LENGTH)))
        vm.onIntent(ProjectEditorIntent.Save)

        assertEquals(null, vm.stateFlow.value.errorMessage)
        assertEquals(1, fakeProjectsRepo.observeAll().first().size)
    }

    @Test
    fun `save rejects a name one character past the limit`() = runTest {
        val vm = createVm(backgroundScope)
        vm.onIntent(ProjectEditorIntent.NameChanged("a".repeat(ProjectsDomain.MAX_NAME_LENGTH + 1)))
        vm.onIntent(ProjectEditorIntent.Save)

        assertNotNull(vm.stateFlow.value.errorMessage)
        assertTrue(fakeProjectsRepo.observeAll().first().isEmpty())
    }

    @Test
    fun `save accepts 50 emoji that measure 100 utf-16 units`() = runTest {
        // The case the old private `name.length > 50` check rejected: 50 visible
        // characters, 100 UTF-16 units, under a limit the message states in characters.
        val name = "🎉".repeat(ProjectsDomain.MAX_NAME_LENGTH)
        assertEquals(100, name.length, "precondition: 100 UTF-16 units")

        val vm = createVm(backgroundScope)
        vm.onIntent(ProjectEditorIntent.NameChanged(name))
        vm.onIntent(ProjectEditorIntent.Save)

        assertEquals(null, vm.stateFlow.value.errorMessage)
        assertEquals(1, fakeProjectsRepo.observeAll().first().size)
    }

    @Test
    fun `editor and domain agree on the error for an over-long name`() = runTest {
        val name = "🎉".repeat(ProjectsDomain.MAX_NAME_LENGTH + 1)
        val domainError = ProjectsDomain.validateName(name)

        val vm = createVm(backgroundScope)
        vm.onIntent(ProjectEditorIntent.NameChanged(name))
        vm.onIntent(ProjectEditorIntent.Save)

        assertEquals(domainError?.message, vm.stateFlow.value.errorMessage)
    }
}

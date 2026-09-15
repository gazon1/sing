package com.singularity.todo.core.ui.components

import app.cash.turbine.test
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.projects.CreateProjectUseCase
import com.singularity.todo.feature.projects.Project
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.projects.ProjectsRepository
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [ProjectPickerViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectPickerViewModelTest {

    private val testUserId = UserId("test-user")
    private val testProjects = listOf(
        Project(
            id = ProjectId.generate(),
            name = "Work",
            color = 0xFF4CAF50.toInt(),
            createdAt = Clock.now(),
            updatedAt = Clock.now(),
            userId = testUserId,
        ),
        Project(
            id = ProjectId.generate(),
            name = "Personal",
            color = 0xFF2196F3.toInt(),
            createdAt = Clock.now(),
            updatedAt = Clock.now(),
            userId = testUserId,
        ),
    )

    private fun createFakeCurrentUser(): ProfileAwareCurrentUser {
        val authRepo = FakeAuthRepository(initialSession = com.singularity.todo.core.auth.Session.Anonymous(testUserId))
        return FakeProfileAwareCurrentUser(authRepository = authRepo)
    }

    private fun createFakeRepo(): FakeProjectsRepository {
        val repo = FakeProjectsRepository()
        repo.seed(testProjects[0], testProjects[1])
        return repo
    }

    private fun createVm(
        repo: ProjectsRepository = createFakeRepo(),
        createProject: CreateProjectUseCase = CreateProjectUseCase(repo, Clock),
        currentUser: ProfileAwareCurrentUser = createFakeCurrentUser(),
    ) = ProjectPickerViewModel(
        projectRepo = repo,
        createProject = createProject,
        currentUser = currentUser,
        sharingStarted = { SharingStarted.WhileSubscribed(0) },
    )

    @Test
    fun `projects emits list on startup`() = runTest {
        val repo = createFakeRepo()
        val vm = createVm(repo = repo)
        vm.projectsFlow.test {
            val projects = awaitItem()
            assertEquals(2, projects.size)
            assertEquals("Work", projects[0].name)
            assertEquals("Personal", projects[1].name)
        }
    }

    @Test
    fun `setDraftName updates draftName`() = runTest {
        val vm = createVm()
        assertEquals("", vm.draftName.value)

        vm.setDraftName("New Project")
        assertEquals("New Project", vm.draftName.value)
    }

    @Test
    fun `setCreating true-false`() = runTest {
        val vm = createVm()
        assertFalse(vm.isCreating.value)

        vm.setCreating(true)
        assertTrue(vm.isCreating.value)

        vm.setCreating(false)
        assertFalse(vm.isCreating.value)
    }

    @Test
    fun `setCreating false clears draftName`() = runTest {
        val vm = createVm()
        vm.setDraftName("Some name")
        assertEquals("Some name", vm.draftName.value)

        vm.setCreating(false)
        assertEquals("", vm.draftName.value)
    }

    @Test
    fun `confirmCreate with blank name does nothing`() = runTest {
        val repo = createFakeRepo()
        val vm = createVm(repo = repo)

        vm.setCreating(true)
        assertTrue(vm.isCreating.value)
        vm.setDraftName("")
        vm.confirmCreate()
        advanceUntilIdle()

        // Blank name causes confirmCreate to return early; isCreating stays true.
        assertTrue(vm.isCreating.value)
    }

    @Test
    fun `confirmCreate emits Created event on success`() = runTest {
        val repo = createFakeRepo()
        val vm = createVm(repo = repo)

        vm.events.test {
            vm.setCreating(true)
            vm.setDraftName("New Project")
            vm.confirmCreate()
            advanceUntilIdle()
            assertIs<ProjectPickerEvent.Created>(awaitItem())
        }
    }

    @Test
    fun `confirmCreate clears creating mode and draftName on success`() = runTest {
        val repo = createFakeRepo()
        val vm = createVm(repo = repo)

        // Subscribe to events so the test scheduler processes the VM's viewModelScope.
        vm.events.test {
            vm.setCreating(true)
            vm.setDraftName("New Project")
            vm.confirmCreate()
            advanceUntilIdle()
            // Drain the Created event so Turbine doesn't complain on test exit.
            awaitItem()
            assertFalse(vm.isCreating.value)
            assertEquals("", vm.draftName.value)
        }
    }

    @Test
    fun `confirmCreate emits Error on failure`() = runTest {
        val failingRepo = FailingProjectsRepository()
        val vm = createVm(repo = failingRepo)

        vm.events.test {
            vm.setCreating(true)
            vm.setDraftName("New Project")
            vm.confirmCreate()
            advanceUntilIdle()
            assertIs<ProjectPickerEvent.Error>(awaitItem())
        }
    }
}

/** In-memory repo for [ProjectsRepository] used in tests. */
private class FakeProjectsRepository : ProjectsRepository {
    private val store = mutableMapOf<String, Project>()

    fun seed(vararg projects: Project) {
        projects.forEach { store[it.id.value] = it }
    }

    override fun watchProjects(userId: UserId) = flowOf(
        store.values.filter { it.userId == userId && !it.isDeleted }
    )
    override fun watchProject(id: ProjectId) = flowOf(store[id.value])
    override suspend fun getById(id: ProjectId) = store[id.value]
    override fun changes(id: ProjectId) = flowOf(store[id.value])
    override fun watchProjectsWithCounts(userId: UserId) = flowOf(emptyList<com.singularity.todo.core.database.ProjectWithCountRow>())
    override fun watchByParent(parentId: ProjectId) = flowOf(emptyList<Project>())
    override suspend fun setParent(id: ProjectId, parentId: ProjectId?, updatedAt: Long) {}
    override suspend fun setSortOrder(id: ProjectId, sortOrder: Int, updatedAt: Long) {}
    override suspend fun restore(id: ProjectId) = Result.success(Unit)
    override suspend fun findByIdempotencyKey(key: String) = null
    override suspend fun create(project: Project) = Result.success(Unit)
    override suspend fun update(project: Project) = Result.success(Unit)
    override suspend fun delete(id: ProjectId) = Result.success(Unit)
}

/** Always-fails repo for error-path tests. */
private class FailingProjectsRepository : ProjectsRepository {
    override fun watchProjects(userId: UserId) = flowOf(emptyList<Project>())
    override fun watchProject(id: ProjectId) = flowOf<Project?>(null)
    override suspend fun getById(id: ProjectId): Project? = null
    override fun changes(id: ProjectId) = flowOf<Project?>(null)
    override fun watchProjectsWithCounts(userId: UserId) = flowOf(emptyList<com.singularity.todo.core.database.ProjectWithCountRow>())
    override fun watchByParent(parentId: ProjectId) = flowOf(emptyList<Project>())
    override suspend fun setParent(id: ProjectId, parentId: ProjectId?, updatedAt: Long) {}
    override suspend fun setSortOrder(id: ProjectId, sortOrder: Int, updatedAt: Long) {}
    override suspend fun restore(id: ProjectId) = Result.success(Unit)
    override suspend fun findByIdempotencyKey(key: String) = null
    override suspend fun create(project: Project) = Result.failure<Unit>(IllegalStateException("Intentional failure"))
    override suspend fun update(project: Project) = Result.success(Unit)
    override suspend fun delete(id: ProjectId) = Result.success(Unit)
}

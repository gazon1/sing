package com.singularity.todo.feature.projects

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.core.platform.Clock
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Integration tests for project lifecycle: create → assign tasks → delete guard.
 * Uses DeleteProjectUseCase directly (no VM coroutine timing issues).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectLifecycleIntegrationTest {

    private val testUserId = UserId("test-user")
    private val fakeProjectRepo = FakeProjectsRepository()
    private val fakeTaskRepo = FakeTaskRepository()

    private fun createDeleteProjectUseCase(): DeleteProjectUseCase = DeleteProjectUseCase(fakeProjectRepo, fakeTaskRepo)

    @Test
    fun `delete empty project → succeeds`() = runTest {
        fakeProjectRepo.seed(
            Project(
                id = ProjectId.fromString("p1"),
                userId = testUserId,
                name = "Empty Project",
                color = 0xFF0000,
                createdAt = Clock.now(),
                updatedAt = Clock.now(),
            ),
        )
        val useCase = createDeleteProjectUseCase()

        useCase(ProjectId.fromString("p1"))
        advanceUntilIdle()

        // Soft-delete sets isDeleted = true; verify via watchProjects flow
        val projectList = fakeProjectRepo.watchProjects(testUserId).first()
        assertTrue(projectList.isEmpty(), "Deleted project should be filtered from watchProjects")
        assertTrue(fakeProjectRepo.watchProject(ProjectId.fromString("p1")).first()?.isDeleted == true)
    }

    @Test
    fun `delete project with tasks → blocked by guard`() = runTest {
        val projectId = ProjectId.fromString("p2")
        fakeProjectRepo.seed(
            Project(
                id = projectId,
                userId = testUserId,
                name = "Project with tasks",
                color = 0xFF0000,
                createdAt = Clock.now(),
                updatedAt = Clock.now(),
            ),
        )
        fakeTaskRepo.seed(
            com.singularity.todo.feature.tasks.domain.model.Task(
                id = com.singularity.todo.feature.tasks.domain.model.TaskId.fromString("t1"),
                userId = testUserId,
                title = "Active task",
                projectId = projectId,
                createdAt = Clock.now(),
                updatedAt = Clock.now(),
            ),
        )
        val useCase = createDeleteProjectUseCase()

        val result = useCase(projectId)
        advanceUntilIdle()

        assertTrue(result.isFailure)
        // Project should NOT be soft-deleted (guard blocked deletion)
        val project = fakeProjectRepo.watchProject(projectId).first()
        assertTrue(project?.isDeleted == false)
    }
}

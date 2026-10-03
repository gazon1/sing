@file:Suppress("NoDirectClockSystem")

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.projects

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.CreateProjectInput
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.projects.domain.usecase.CreateProjectUseCase
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.projects.domain.usecase.UpdateProjectUseCase
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.FakeAuthRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class ProjectsUseCaseTest {
    private val testUserId = UserId("test-user")
    private val fakeProjectRepo = FakeProjectsRepository()
    private val fakeTaskRepo = FakeTaskRepository()
    private val fakeCurrentUser = FakeProfileAwareCurrentUser(
        FakeAuthRepository(
            initialSession = com.singularity.todo.core.auth.Session.Anonymous(testUserId),
        ),
    )

    private fun createProjectUseCase() = CreateProjectUseCase(fakeProjectRepo, Clock.System, fakeCurrentUser)
    private fun updateProjectUseCase() = UpdateProjectUseCase(fakeProjectRepo, Clock.System)
    private fun deleteProjectUseCase() = DeleteProjectUseCase(fakeProjectRepo, fakeTaskRepo)

    // ─── CreateProjectUseCase ───────────────────────────────────────────────

    @Test
    fun `create project with valid input returns ProjectId`() = runTest {
        val input = CreateProjectInput(
            name = "Work",
            color = 0xFF2196F3.toInt(),
        )
        val result = createProjectUseCase()(input)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.value?.isNotEmpty() == true)
        assertTrue(result.getOrNull()?.value != null)
    }

    @Test
    fun `create project with blank name fails`() = runTest {
        val input = CreateProjectInput(
            name = "   ",
            color = 0xFF2196F3.toInt(),
        )
        val result = createProjectUseCase()(input)
        assertTrue(result.isFailure)
    }

    @Test
    fun `create project with name longer than 50 chars fails`() = runTest {
        val input = CreateProjectInput(
            name = "A".repeat(51),
            color = 0xFF2196F3.toInt(),
        )
        val result = createProjectUseCase()(input)
        assertTrue(result.isFailure)
    }

    @Test
    fun `create project with transparent color fails`() = runTest {
        val input = CreateProjectInput(
            name = "Work",
            color = 0x002196F3, // transparent alpha
        )
        val result = createProjectUseCase()(input)
        assertTrue(result.isFailure)
    }

    @Test
    fun `create project with icon and parentId succeeds`() = runTest {
        val input = CreateProjectInput(
            name = "Sub Project",
            color = 0xFFFF0000.toInt(),
            icon = "work",
            parentId = ProjectId.fromString("parent1"),
        )
        val result = createProjectUseCase()(input)
        assertTrue(result.isSuccess)
    }

    // ─── UpdateProjectUseCase ───────────────────────────────────────────────

    @Test
    fun `update project name succeeds`() = runTest {
        // Create first
        val id = createProjectUseCase()(
            CreateProjectInput(name = "Old", color = 0xFF2196F3.toInt()),
        ).getOrNull()!!

        val result = updateProjectUseCase()(id) { it.copy(name = "New Name") }
        assertTrue(result.isSuccess)

        // Verify via repository
        val updated = fakeProjectRepo.observeProject(id).first()
        assertEquals("New Name", updated?.name)
    }

    @Test
    fun `update non-existent project fails`() = runTest {
        val fakeId = ProjectId.fromString("nonexistent")
        val result = updateProjectUseCase()(fakeId) { it.copy(name = "New") }
        assertTrue(result.isFailure)
    }

    // ─── DeleteProjectUseCase ─────────────────────────────────────────────

    @Test
    fun `delete empty project succeeds`() = runTest {
        val id = createProjectUseCase()(
            CreateProjectInput(name = "Empty", color = 0xFF2196F3.toInt()),
        ).getOrNull()!!

        val result = deleteProjectUseCase()(id)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `delete project with tasks fails with Validation error`() = runTest {
        val id = createProjectUseCase()(
            CreateProjectInput(name = "With Tasks", color = 0xFF2196F3.toInt()),
        ).getOrNull()!!

        // Seed a task belonging to this project
        val now = Clock.System.now()
        fakeTaskRepo.seed(
            Task(
                id = TaskId.fromString("t1"),
                title = "Active task",
                priority = TaskPriority.Medium,
                userId = testUserId,
                projectId = id,
                createdAt = now,
                updatedAt = now,
            ),
        )

        val result = deleteProjectUseCase()(id)
        assertTrue(result.isFailure)
    }
}

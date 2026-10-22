package com.singularity.todo.feature.tasks.domain.usecase

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Covers [TaskReopenUseCase]: the reopen that brings a closed project back with
 * the task, and the close that the repository refuses while a prerequisite is
 * open.
 */
@Tag("fast")
class TaskReopenUseCaseTest {

    private val now: Instant = Instant.parse("2026-01-15T12:00:00Z")
    private val userId = UserId("test-user")
    private val clock = FakeClock(now)

    private val tasks = FakeTaskRepository()
    private val projects = FakeProjectsRepository()

    private fun useCase() = TaskReopenUseCase(tasks, projects, clock)

    private fun task(id: String, title: String = "Task $id", projectId: ProjectId? = null) = Task(
        id = TaskId(id),
        title = title,
        projectId = projectId,
        createdAt = now,
        updatedAt = now,
        userId = userId,
    )

    private fun project(id: String, closedAt: Instant? = null) = Project(
        id = ProjectId(id),
        name = "Project $id",
        color = 0xFF5500,
        closedAt = closedAt,
        createdAt = now,
        updatedAt = now,
        userId = userId,
    )

    @Test
    fun `the repository refuses a close while a prerequisite is open`() = runTest {
        tasks.seed(task("dep"), task("t1").copy(blockedBy = setOf(TaskId("dep"))))

        val completed = tasks.complete(TaskId("t1")).getOrThrow()

        assertTrue(!completed, "the write must refuse while a prerequisite is open")
        assertNull(tasks.tasks.value["t1"]?.completedAt, "nothing may be written")
    }

    @Test
    fun `a forced close goes through`() = runTest {
        tasks.seed(task("dep"), task("t1").copy(blockedBy = setOf(TaskId("dep"))))

        val completed = tasks.complete(TaskId("t1"), force = true).getOrThrow()

        assertTrue(completed, "the confirmed close must land")
        assertNotNull(tasks.tasks.value["t1"]?.completedAt)
    }

    @Test
    fun `reopening a task in a closed project opens the project too`() = runTest {
        projects.seed(project("p1", closedAt = now))
        tasks.seed(task("t1", projectId = ProjectId("p1")).copy(completedAt = now))

        useCase().reopen(TaskId("t1")).getOrThrow()

        assertNull(tasks.tasks.value["t1"]?.completedAt, "the task is back in work")
        val project = projects.get(ProjectId("p1"))
        assertNull(project?.closedAt, "the project it belongs to is open again")
    }

    @Test
    fun `reopening a task in an open project leaves the project alone`() = runTest {
        projects.seed(project("p1"))
        tasks.seed(task("t1", projectId = ProjectId("p1")).copy(completedAt = now))

        useCase().reopen(TaskId("t1")).getOrThrow()

        assertNull(tasks.tasks.value["t1"]?.completedAt)
        assertNull(projects.get(ProjectId("p1"))?.closedAt)
    }

    @Test
    fun `reopening a task without a project changes only the task`() = runTest {
        tasks.seed(task("t1").copy(completedAt = now))

        useCase().reopen(TaskId("t1")).getOrThrow()

        assertNull(tasks.tasks.value["t1"]?.completedAt)
    }

    @Test
    fun `the project is the one the task belongs to`() = runTest {
        projects.seed(project("p1", closedAt = now), project("p2", closedAt = now))
        tasks.seed(task("t1", projectId = ProjectId("p2")).copy(completedAt = now))

        useCase().reopen(TaskId("t1")).getOrThrow()

        assertNull(projects.get(ProjectId("p2"))?.closedAt, "the task's own project is open")
        assertNotNull(projects.get(ProjectId("p1"))?.closedAt, "the other project stays closed")
    }
}

package com.singularity.todo.feature.projects.domain.usecase

import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeUnitOfWork
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Covers [CloseProjectUseCase]: the cascade to the project's open tasks, and the
 * fail-fast that writes nothing when one of them is blocked.
 */
@Tag("fast")
class CloseProjectUseCaseTest {

    private val now: Instant = Instant.parse("2026-01-15T12:00:00Z")
    private val userId = UserId("test-user")
    private val projectId = ProjectId("p1")

    private val projects = FakeProjectsRepository()
    private val tasks = FakeTaskRepository()

    private fun useCase() = CloseProjectUseCase(
        projectRepo = projects,
        taskRepo = tasks,
        clock = FakeClock(now),
        unitOfWork = FakeUnitOfWork(),
    )

    private fun project(id: String = "p1", name: String = "Project") = Project(
        id = ProjectId(id),
        name = name,
        color = 0xFF5500,
        createdAt = now,
        updatedAt = now,
        userId = userId,
    )

    private fun task(id: String, title: String = "Task", projectId: ProjectId? = null) = Task(
        id = TaskId(id),
        title = title,
        projectId = projectId,
        createdAt = now,
        updatedAt = now,
        userId = userId,
    )

    @Test
    fun `closing a project closes its open tasks and the project`() = runTest {
        projects.seed(project())
        tasks.seed(task("t1", "Open", projectId), task("t2", "Done", projectId).copy(completedAt = now))

        val outcome = useCase().close(projectId).getOrThrow()

        assertTrue(outcome is ProjectCloseOutcome.Closed, "outcome=$outcome")
        assertEquals(1, (outcome as ProjectCloseOutcome.Closed).closedTaskCount)
        assertNotNull(tasks.tasks.value["t1"]?.completedAt, "the open task must be closed")
        assertEquals(now, tasks.tasks.value["t2"]?.completedAt, "the closed task must be untouched")
        assertNotNull(projects.get(projectId)?.closedAt, "the project must be closed")
    }

    @Test
    fun `a project's tasks are closed without a further occurrence for a repeating one`() = runTest {
        projects.seed(project())
        val recurring = task("t1", "Repeating", projectId).copy(
            recurrence = com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval(
                base = com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase.FROM_DUE,
                amount = 1,
                unit = kotlinx.datetime.DateTimeUnit.DAY,
            ),
        )
        tasks.seed(recurring)

        useCase().close(projectId).getOrThrow()

        assertNotNull(tasks.tasks.value["t1"]?.completedAt)
        assertEquals(
            1,
            tasks.tasks.value.size,
            "a closed project must not schedule another occurrence",
        )
    }

    @Test
    fun `another project's tasks are left alone`() = runTest {
        projects.seed(project(), project("p2", "Other"))
        tasks.seed(task("t1", "Mine", projectId), task("t2", "Theirs", ProjectId("p2")))

        useCase().close(projectId).getOrThrow()

        assertNotNull(tasks.tasks.value["t1"]?.completedAt)
        assertNull(tasks.tasks.value["t2"]?.completedAt, "another project's task must be left alone")
    }

    @Test
    fun `a blocked task stops the close and nothing is written`() = runTest {
        projects.seed(project())
        val dep = task("dep", "Dependency")
        val blocked = task("t1", "Blocked", projectId).copy(blockedBy = setOf(TaskId("dep")))
        tasks.seed(dep, blocked)

        val outcome = useCase().close(projectId).getOrThrow()

        assertTrue(outcome is ProjectCloseOutcome.NeedsConfirmation, "outcome=$outcome")
        val needs = outcome as ProjectCloseOutcome.NeedsConfirmation
        assertEquals(listOf("Blocked"), needs.blockedTasks.map { it.title })
        assertEquals(1, needs.openTaskCount)
        assertNull(tasks.tasks.value["t1"]?.completedAt, "nothing may be written")
        assertNull(projects.get(projectId)?.closedAt, "the project must stay open")
    }

    @Test
    fun `confirming the close writes past the blocked task`() = runTest {
        projects.seed(project())
        val dep = task("dep", "Dependency")
        val blocked = task("t1", "Blocked", projectId).copy(blockedBy = setOf(TaskId("dep")))
        tasks.seed(dep, blocked)

        val outcome = useCase().close(projectId, force = true).getOrThrow()

        assertTrue(outcome is ProjectCloseOutcome.Closed, "outcome=$outcome")
        assertNotNull(tasks.tasks.value["t1"]?.completedAt)
        assertNotNull(projects.get(projectId)?.closedAt)
    }

    @Test
    fun `a closed project leaves the project list`() = runTest {
        projects.seed(project())
        tasks.seed(task("t1", "Open", projectId))

        useCase().close(projectId).getOrThrow()

        val listed = projects.observeAll().first()
        assertTrue(listed.none { it.id == projectId }, "a closed project must leave the list")
        assertNotNull(projects.get(projectId), "but it must still be readable")
    }
}

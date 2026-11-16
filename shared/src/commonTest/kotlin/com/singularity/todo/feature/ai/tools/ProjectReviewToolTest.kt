package com.singularity.todo.feature.ai.tools

import ai.koog.prompt.message.MessagePart
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.projects.domain.model.Project
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeTasksSettingsRepository
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The project brief, tested through what it hands the model.
 *
 * The brief is the one place where the review is grounded in the app's rows instead
 * of in a name the model made up, so the claims worth pinning are the ones that
 * make the grounding true: a task of another project is not a member, a dependency
 * that lives elsewhere is a blocker and not a member, the counts cover the whole
 * project even when the list is cut, and the notes are read in one batch.
 */
@Tag("fast")
class ProjectReviewToolTest {
    private val zone = TimeZone.UTC

    private val zoneProvider =
        object : TimeZoneProvider {
            override fun current(): TimeZone = zone
        }

    private val clock = FakeClock(Instant.parse("2026-03-10T09:00:00Z"))

    private fun task(
        id: String,
        title: String = id,
        dueDate: LocalDate? = null,
        dueTime: LocalTime? = null,
        priority: TaskPriority = TaskPriority.None,
        projectId: ProjectId? = null,
        completedAt: Instant? = null,
        blockedBy: Set<TaskId> = emptySet(),
    ): Task =
        Task(
            id = TaskId(id),
            title = title,
            description = null,
            priority = priority,
            projectId = projectId,
            parentTaskId = null,
            tags = emptyList(),
            dueDate = dueDate,
            dueTime = dueTime,
            estimateMinutes = null,
            completedAt = completedAt,
            blockedBy = blockedBy,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-03-01T00:00:00Z"),
            userId = TestUsers.DEFAULT,
        )

    private fun project(
        id: String,
        name: String = "Launch",
    ): Project =
        Project(
            id = ProjectId(id),
            name = name,
            color = 0,
            description = "Ship the launch",
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-03-01T00:00:00Z"),
            userId = TestUsers.DEFAULT,
        )

    private fun note(
        id: String,
        title: String,
        taskId: String,
    ): Note =
        Note(
            id = NoteId(id),
            userId = TestUsers.DEFAULT,
            title = title,
            bodyMarkdown = "Some context about $title",
            taskId = TaskId(taskId),
            createdAt = Instant.parse("2026-02-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-02-01T00:00:00Z"),
        )

    private fun tool(
        executor: FakePlanExecutor,
        tasks: FakeTaskRepository = FakeTaskRepository(),
        projects: FakeProjectsRepository = FakeProjectsRepository(),
        notes: FakeNotesRepository = FakeNotesRepository(),
        stalenessDays: Int = 21,
    ) = ProjectReviewTool(
        promptExecutor = executor,
        model = FakePlanExecutor.TEST_MODEL,
        taskRepository = tasks,
        projectsRepository = projects,
        notesRepository = notes,
        clock = clock,
        timeZoneProvider = zoneProvider,
        tasksSettingsRepository = FakeTasksSettingsRepository(stalenessDays),
    )

    private fun textOf(executor: FakePlanExecutor): String =
        executor.lastPrompt!!.messages.joinToString("\n") { message ->
            message.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
        }

    @Test
    fun `the brief carries the project's own tasks and their health`() =
        runTest {
            val tasks = FakeTaskRepository()
            tasks.add(
                task(
                    id = "late",
                    title = "Ship the report",
                    dueDate = LocalDate(2026, 3, 8),
                    dueTime = LocalTime(17, 0),
                    projectId = ProjectId("launch"),
                ),
            )
            tasks.add(
                task(
                    id = "elsewhere",
                    title = "Someone else's task",
                    projectId = ProjectId("other"),
                ),
            )
            val projects = FakeProjectsRepository()
            projects.add(project("launch"))
            val executor = FakePlanExecutor("A short paragraph.")

            tool(executor, tasks, projects).execute(ProjectReviewInput(projectId = "launch"))

            val text = textOf(executor)
            listOf(
                "Project: Launch (id: launch)",
                "Goal: Ship the launch",
                "\"title\":\"Ship the report\"",
                "\"health\":\"overdue\"",
                "1 overdue",
            ).forEach { fact ->
                assertTrue(text.contains(fact), "the brief must carry $fact:\n$text")
            }
            assertFalse(
                text.contains("Someone else's task"),
                "a task of another project is not a member:\n$text",
            )
        }

    @Test
    fun `a dependency from another project is a blocker and not a member`() =
        runTest {
            val tasks = FakeTaskRepository()
            tasks.add(task(id = "prereq", title = "Get sign-off", projectId = ProjectId("other")))
            tasks.add(
                task(
                    id = "waiting",
                    title = "Publish the release",
                    projectId = ProjectId("launch"),
                    blockedBy = setOf(TaskId("prereq")),
                ),
            )
            val projects = FakeProjectsRepository()
            projects.add(project("launch"))
            val executor = FakePlanExecutor("A short paragraph.")

            tool(executor, tasks, projects).execute(ProjectReviewInput(projectId = "launch"))

            val text = textOf(executor)
            assertTrue(text.contains("\"blockedBy\":[{\"id\":\"prereq\",\"title\":\"Get sign-off\"}]"), text)
            assertTrue(text.contains("\"health\":\"blocked\""), text)
            assertEquals(1, Regex("\"projectId\":\"launch\"").findAll(text).count())
        }

    @Test
    fun `the counts cover the whole project, not the page that was carried`() =
        runTest {
            val tasks = FakeTaskRepository()
            repeat(55) { index ->
                tasks.add(
                    task(
                        id = "t-" + index.toString().padStart(2, '0'),
                        title = "Task $index",
                        projectId = ProjectId("launch"),
                    ),
                )
            }
            val projects = FakeProjectsRepository()
            projects.add(project("launch"))
            val executor = FakePlanExecutor("A short paragraph.")

            tool(executor, tasks, projects).execute(ProjectReviewInput(projectId = "launch"))

            val text = textOf(executor)
            assertTrue(text.contains("Tasks: 55 in the project, 55 open, 0 completed."), text)
            assertTrue(text.contains("5 more open task(s) were left out"), text)
            assertEquals(50, Regex("\"id\":\"t-").findAll(text).count())
        }

    @Test
    fun `the notes of the project's tasks are read once and carried`() =
        runTest {
            val tasks = FakeTaskRepository()
            tasks.add(task(id = "one", title = "First task", projectId = ProjectId("launch")))
            tasks.add(task(id = "two", title = "Second task", projectId = ProjectId("launch")))
            val projects = FakeProjectsRepository()
            projects.add(project("launch"))
            val notes = FakeNotesRepository(projectOfTask = { id -> tasks.tasks.value[id.value]?.projectId })
            notes.add(note("n1", "Kickoff notes", "one"))
            notes.add(note("n2", "Second thoughts", "two"))
            val executor = FakePlanExecutor("A short paragraph.")

            tool(executor, tasks, projects, notes).execute(ProjectReviewInput(projectId = "launch"))

            val text = textOf(executor)
            assertTrue(text.contains("Kickoff notes"), text)
            assertTrue(text.contains("Some context about Kickoff notes"), text)
            assertEquals(
                1,
                notes.watchForProjectCalls,
                "the notes of a project are read in one batch, not one read per task",
            )
            assertEquals(
                1,
                notes.countForProjectCalls,
                "the total behind the page is counted in one read",
            )
        }

    @Test
    fun `a project with no tasks and no goal still reads as a project`() =
        runTest {
            val projects = FakeProjectsRepository()
            projects.add(project("empty", name = "Empty").copy(description = null))
            val executor = FakePlanExecutor("A short paragraph.")

            tool(executor, projects = projects).execute(ProjectReviewInput(projectId = "empty"))

            val text = textOf(executor)
            assertTrue(text.contains("Project: Empty (id: empty)"), text)
            assertTrue(text.contains("Tasks: 0 in the project, 0 open, 0 completed."), text)
            assertFalse(text.contains("Goal:"), "a project without a goal states none:\n$text")
            assertFalse(text.contains("Notes attached"), "there are no notes to attach:\n$text")
        }

    @Test
    fun `the notes are cut and the omission is stated`() =
        runTest {
            val tasks = FakeTaskRepository()
            tasks.add(task(id = "one", title = "First task", projectId = ProjectId("launch")))
            val projects = FakeProjectsRepository()
            projects.add(project("launch"))
            val notes = FakeNotesRepository(projectOfTask = { id -> tasks.tasks.value[id.value]?.projectId })
            repeat(25) { index ->
                notes.add(note("n" + index.toString().padStart(2, '0'), "Note $index", "one"))
            }
            val executor = FakePlanExecutor("A short paragraph.")

            tool(executor, tasks, projects, notes).execute(ProjectReviewInput(projectId = "launch"))

            val text = textOf(executor)
            assertTrue(text.contains("5 more note(s) attached to the project's tasks were left out"), text)
            assertEquals(20, Regex("\"taskId\":\"one\"").findAll(text).count())
        }

    @Test
    fun `a project id that names no project says so instead of reviewing a name`() =
        runTest {
            val executor = FakePlanExecutor("A short paragraph.")

            val answer =
                tool(executor).execute(ProjectReviewInput(projectName = "Launch", projectId = "ghost"))

            assertEquals("No project with id ghost exists.", answer)
            assertNull(executor.lastPrompt)
        }

    @Test
    fun `the old way in still reviews the titles the caller names`() =
        runTest {
            val executor = FakePlanExecutor("A short paragraph.")

            tool(executor).execute(ProjectReviewInput(projectName = "Launch", tasks = listOf("Ship it")))

            val text = textOf(executor)
            assertTrue(text.contains("Project: Launch"), text)
            assertTrue(text.contains("Ship it"), text)
        }
}

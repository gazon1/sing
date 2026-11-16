package com.singularity.todo.feature.ai.tools

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.MessagePart
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tasks.domain.logic.TaskSelection
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeTasksSettingsRepository
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The planner, tested through what it hands the model and what it does with the answer.
 *
 * The two claims worth pinning are the ones the old tool could not make: the model is
 * given facts about the tasks rather than their titles, and those facts come from the
 * app's own selector and health rules. Everything else here — truncation, the local-day
 * boundary for completions, the output shape — follows from those two.
 */
@Tag("fast")
class WeeklyPlanToolTest {
    private val zone = TimeZone.UTC

    private val zoneProvider =
        object : TimeZoneProvider {
            override fun current(): TimeZone = zone
        }

    private fun task(
        id: String,
        title: String = id,
        dueDate: LocalDate? = null,
        dueTime: LocalTime? = null,
        priority: TaskPriority = TaskPriority.None,
        estimateMinutes: Int? = null,
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
            estimateMinutes = estimateMinutes,
            completedAt = completedAt,
            blockedBy = blockedBy,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-03-01T00:00:00Z"),
            userId = TestUsers.DEFAULT,
        )

    private fun tool(
        executor: FakePlanExecutor,
        repo: FakeTaskRepository,
        clock: FakeClock = FakeClock(Instant.parse("2026-03-10T09:00:00Z")),
        stalenessDays: Int = 21,
    ) = WeeklyPlanTool(
        promptExecutor = executor,
        model = FakePlanExecutor.TEST_MODEL,
        taskRepository = repo,
        clock = clock,
        timeZoneProvider = zoneProvider,
        tasksSettingsRepository = FakeTasksSettingsRepository(stalenessDays),
    )

    private fun promptText(prompt: Prompt): String =
        prompt.messages.joinToString("\n") { message ->
            message.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
        }

    private fun textOf(executor: FakePlanExecutor): String = promptText(executor.lastPrompt!!)

    @Test
    fun `the model receives task facts, not titles alone`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.add(
                task(
                    id = "overdue",
                    title = "Ship report",
                    dueDate = LocalDate(2026, 3, 8),
                    dueTime = LocalTime(17, 0),
                    priority = TaskPriority.High,
                    estimateMinutes = 90,
                    projectId = ProjectId("work"),
                ),
            )
            repo.add(task(id = "blocker", title = "Get sign-off"))
            repo.add(
                task(
                    id = "blocked",
                    title = "Publish release",
                    blockedBy = setOf(TaskId("blocker")),
                ),
            )
            val executor = FakePlanExecutor("""{"items":[]}""")

            tool(executor, repo).execute(WeeklyPlanInput(selection = TaskSelection.ACTIVE_NOT_DEFERRED))

            val text = textOf(executor)
            listOf(
                "\"id\":\"overdue\"",
                "\"title\":\"Ship report\"",
                "\"dueDate\":\"2026-03-08\"",
                "\"dueTime\":\"17:00\"",
                "\"priority\":\"High\"",
                "\"estimateMinutes\":90",
                "\"health\":\"overdue\"",
                "\"projectId\":\"work\"",
                "\"blockedBy\":[{\"id\":\"blocker\",\"title\":\"Get sign-off\"}]",
                "\"health\":\"blocked\"",
            ).forEach { fact ->
                assertTrue(text.contains(fact), "the prompt must carry $fact:\n$text")
            }
        }

    @Test
    fun `a stored zero means the default window here too`() =
        runTest {
            val repo = FakeTaskRepository()
            // Undated and untouched for nine days: stagnant under a zero-day window,
            // fine under the 21-day default a stored 0 means.
            repo.add(task(id = "undated", title = "Undated"))
            val executor = FakePlanExecutor("""{"items":[]}""")

            tool(executor, repo, stalenessDays = 0)
                .execute(WeeklyPlanInput(selection = TaskSelection.ACTIVE_NOT_DEFERRED))

            val text = textOf(executor)
            assertTrue(text.contains("\"health\":\"fine\""), text)
            assertFalse(text.contains("\"health\":\"stagnant\""), text)
        }

    @Test
    fun `the selection is the read tools' selection`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.add(task(id = "today", dueDate = LocalDate(2026, 3, 10)))
            repo.add(task(id = "later", dueDate = LocalDate(2026, 3, 20)))
            repo.add(task(id = "undated"))
            val clock = FakeClock(Instant.parse("2026-03-10T09:00:00Z"))

            val facts = TaskPlanSupport.facts(TaskSelection.TODAY, repo, clock, zone, stalenessDays = 21)
            val titles = TaskPlanSupport.select(TaskSelection.TODAY, repo, clock, zone, stalenessDays = 21)

            assertEquals(titles, facts.tasks.map { it.title })
            assertEquals(listOf("today"), facts.tasks.map { it.id })
        }

    @Test
    fun `a bounded page says how many tasks were left out`() =
        runTest {
            val repo = FakeTaskRepository()
            repeat(55) { index ->
                repo.add(task(id = "t-" + index.toString().padStart(2, '0'), title = "Task $index"))
            }
            val executor = FakePlanExecutor("""{"items":[]}""")

            tool(executor, repo).execute(WeeklyPlanInput(selection = TaskSelection.ACTIVE_NOT_DEFERRED))

            val text = textOf(executor)
            assertTrue(text.contains("5 more task(s)"), text)
            assertEquals(50, Regex("\"id\":\"t-").findAll(text).count())
        }

    @Test
    fun `the evening review reports completions from the user's local day`() =
        runTest {
            val berlin = TimeZone.of("Europe/Berlin")
            val localProvider =
                object : TimeZoneProvider {
                    override fun current(): TimeZone = berlin
                }
            val clock = FakeClock(Instant.parse("2026-03-10T21:00:00Z"))
            val repo = FakeTaskRepository()
            repo.add(
                task(
                    id = "tonight",
                    title = "Finished tonight",
                    completedAt = Instant.parse("2026-03-10T22:30:00Z"),
                ),
            )
            repo.add(
                task(
                    id = "tomorrow",
                    title = "Finished after midnight",
                    completedAt = Instant.parse("2026-03-10T23:30:00Z"),
                ),
            )
            repo.add(
                task(
                    id = "yesterday",
                    title = "Finished yesterday",
                    completedAt = Instant.parse("2026-03-09T12:00:00Z"),
                ),
            )
            val executor = FakePlanExecutor("""{"items":[]}""")
            val tool =
                WeeklyPlanTool(
                    promptExecutor = executor,
                    model = FakePlanExecutor.TEST_MODEL,
                    taskRepository = repo,
                    clock = clock,
                    timeZoneProvider = localProvider,
                    tasksSettingsRepository = FakeTasksSettingsRepository(),
                )

            tool.execute(WeeklyPlanInput(mode = PlanMode.EVENING))

            val text = textOf(executor)
            assertTrue(text.contains("Today: 2026-03-10 (time zone: Europe/Berlin)"), text)
            assertTrue(text.contains("Completed today (JSON)"), text)
            assertTrue(text.contains("\"title\":\"Finished tonight\""), text)
            assertFalse(
                text.contains("Finished after midnight"),
                "a completion after local midnight is tomorrow's:\n$text",
            )
            assertFalse(
                text.contains("Finished yesterday"),
                "a completion from a previous day is not today's:\n$text",
            )
        }

    @Test
    fun `an evening review without a selection still gets the unfinished tasks`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.add(task(id = "open", title = "Open task"))
            repo.add(task(id = "late", title = "Late task", dueDate = LocalDate(2026, 3, 8)))
            repo.add(task(id = "blocker", title = "Waiting on"))
            repo.add(
                task(
                    id = "blocked",
                    title = "Blocked task",
                    blockedBy = setOf(TaskId("blocker")),
                ),
            )
            repo.add(
                task(
                    id = "done",
                    title = "Done today",
                    completedAt = Instant.parse("2026-03-10T08:00:00Z"),
                ),
            )
            val executor = FakePlanExecutor("""{"items":[]}""")

            tool(executor, repo).execute(WeeklyPlanInput(mode = PlanMode.EVENING))

            val text = textOf(executor)
            listOf(
                "Open task",
                "Late task",
                "\"health\":\"overdue\"",
                "Blocked task",
                "\"health\":\"blocked\"",
                "Completed today (JSON)",
                "Done today",
            ).forEach { fact ->
                assertTrue(text.contains(fact), "the evening review must carry $fact:\n$text")
            }
        }

    @Test
    fun `the completed list is bounded like the open one`() =
        runTest {
            val repo = FakeTaskRepository()
            repeat(55) { index ->
                repo.add(
                    task(
                        id = "done-" + index.toString().padStart(2, '0'),
                        title = "Done $index",
                        completedAt = Instant.parse("2026-03-10T08:00:00Z"),
                    ),
                )
            }
            val executor = FakePlanExecutor("""{"items":[]}""")

            tool(executor, repo).execute(WeeklyPlanInput(mode = PlanMode.EVENING))

            val text = textOf(executor)
            assertEquals(50, Regex("\"completedAt\":\"").findAll(text).count())
            assertTrue(text.contains("5 more task(s) completed today"), text)
        }

    @Test
    fun `the morning briefing does not carry completion facts`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.add(
                task(
                    id = "done",
                    completedAt = Instant.parse("2026-03-10T08:00:00Z"),
                ),
            )
            val executor = FakePlanExecutor("""{"items":[]}""")

            tool(executor, repo).execute(WeeklyPlanInput())

            assertFalse(textOf(executor).contains("Completed today (JSON)"))
        }

    @Test
    fun `the answer becomes focus items, sections and the first action`() =
        runTest {
            val executor =
                FakePlanExecutor(
                    """
                    {"items":["Ship report","Publish release"],
                     "sections":[{"title":"Needs attention","items":["Ship report — overdue 2 days"]}],
                     "firstAction":"Open the report draft"}
                    """.trimIndent(),
                )

            val output =
                Json.decodeFromString(
                    WeeklyPlanOutput.serializer(),
                    tool(executor, FakeTaskRepository()).execute(WeeklyPlanInput(tasks = listOf("Ship report"))),
                )

            assertEquals(listOf("Ship report", "Publish release"), output.items)
            assertEquals("Needs attention", output.sections.single().title)
            assertEquals("Open the report draft", output.firstAction)
        }

    @Test
    fun `an old bare list is still read as the focus set`() =
        runTest {
            val executor = FakePlanExecutor("""["One","Two"]""")

            val output =
                Json.decodeFromString(
                    WeeklyPlanOutput.serializer(),
                    tool(executor, FakeTaskRepository()).execute(WeeklyPlanInput(tasks = listOf("One", "Two"))),
                )

            assertEquals(listOf("One", "Two"), output.items)
            assertTrue(output.sections.isEmpty())
        }

    @Test
    fun `an unreadable answer is an empty plan, not a crash`() =
        runTest {
            val executor = FakePlanExecutor("not json at all")

            val output =
                Json.decodeFromString(
                    WeeklyPlanOutput.serializer(),
                    tool(executor, FakeTaskRepository()).execute(WeeklyPlanInput()),
                )

            assertTrue(output.items.isEmpty())
        }

    @Test
    fun `a provider failure is not swallowed`() =
        runTest {
            val executor = FakePlanExecutor(answer = "", failure = IllegalStateException("no api key"))

            assertFailsWith<IllegalStateException> {
                tool(executor, FakeTaskRepository()).execute(WeeklyPlanInput())
            }
        }

    @Test
    fun `cancellation propagates`() =
        runTest {
            val executor = FakePlanExecutor(answer = "", failure = CancellationException("cancelled"))

            assertFailsWith<CancellationException> {
                tool(executor, FakeTaskRepository()).execute(WeeklyPlanInput())
            }
        }
}

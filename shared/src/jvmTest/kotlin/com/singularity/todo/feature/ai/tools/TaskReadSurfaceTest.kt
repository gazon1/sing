package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.ToolParameterType
import com.singularity.todo.core.ai.decodeTaskPageCursor
import com.singularity.todo.core.platform.TimeZoneProvider
import com.singularity.todo.core.serialization.StableJson
import com.singularity.todo.feature.projects.domain.model.ProjectId
import com.singularity.todo.feature.tags.TagId
import com.singularity.todo.feature.tasks.domain.logic.TaskSelection
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeSettingsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The read surface, tested through the questions a reader asks of it.
 *
 * The read surface is where the app's own data is supposed to reach a model. Two things
 * have to be true for that to be worth anything: the tool has to select what the app
 * would show (so the answer is the user's answer), and it has to say which set it
 * selected (so the reader can ask for a different one).
 */
@Tag("fast")
class TaskReadSurfaceTest {
    private val today = LocalDate(2026, 3, 10)

    private val clock = FakeClock(Instant.parse("2026-03-10T09:00:00Z"))

    private val zone =
        object : TimeZoneProvider {
            override fun current(): TimeZone = TimeZone.UTC
        }

    private fun task(
        id: String,
        title: String = id,
        dueDate: LocalDate? = null,
        priority: TaskPriority = TaskPriority.None,
        tags: List<TagId> = emptyList(),
        projectId: ProjectId? = null,
        completed: Boolean = false,
        someday: Boolean = false,
        pinned: Boolean = false,
        archived: Boolean = false,
        blockedBy: Set<TaskId> = emptySet(),
        updatedAt: Instant = Instant.parse("2026-03-01T00:00:00Z"),
    ): Task =
        Task(
            id = TaskId(id),
            title = title,
            dueDate = dueDate,
            priority = priority,
            tags = tags,
            projectId = projectId,
            completedAt = if (completed) Instant.parse("2026-03-05T00:00:00Z") else null,
            someday = someday,
            isPinned = pinned,
            archivedAt = if (archived) Instant.parse("2026-03-05T00:00:00Z") else null,
            blockedBy = blockedBy,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = updatedAt,
            userId = TestUsers.DEFAULT,
        )

    private fun tool(
        repo: FakeTaskRepository,
        settings: FakeSettingsRepository = FakeSettingsRepository(),
    ) = ListTasksTool(
        taskRepository = repo,
        checklistRepository = FakeChecklistRepository(),
        clock = clock,
        timeZoneProvider = zone,
        tasksSettingsRepository = settings,
    )

    private fun read(output: String): JsonObject = Json.parseToJsonElement(output).jsonObject

    private fun titles(output: String): List<String> = titlesOf(read(output))

    private fun titlesOf(page: JsonObject): List<String> =
        page["tasks"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }

    @Test
    fun `the tool tells the model which sets it can ask for`() {
        val descriptor = tool(FakeTaskRepository()).descriptor
        val selection = descriptor.optionalParameters.first { it.name == "selection" }
        val type = selection.type as ToolParameterType.Enum

        assertEquals(
            TaskSelection.entries.map { TaskSelection.serializer().descriptor.getElementName(it.ordinal) },
            type.entries.toList(),
            "the schema must offer exactly the vocabulary the tool accepts",
        )
    }

    @Test
    fun `active_not_deferred is every unfinished task that is not deferred, dated or undated`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.seed(
                task("undated"),
                task("dated", dueDate = LocalDate(2026, 3, 9)),
                task("deferred", someday = true),
                task("done", completed = true),
                task("gone", archived = true),
            )

            val output = tool(repo).execute(ListTasksInput(selection = TaskSelection.ACTIVE_NOT_DEFERRED))

            assertEquals(setOf("undated", "dated"), titles(output).toSet())
        }

    @Test
    fun `overdue is the app's overdue`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.seed(
                task("late", dueDate = LocalDate(2026, 3, 1)),
                task("today", dueDate = today),
                task("done-late", dueDate = LocalDate(2026, 3, 1), completed = true),
            )

            val output = tool(repo).execute(ListTasksInput(selection = TaskSelection.OVERDUE))

            assertEquals(listOf("late"), titles(output))
        }

    @Test
    fun `blocked is the app's blocked`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.seed(
                task("waiting", blockedBy = setOf(TaskId("prerequisite"))),
                task("prerequisite"),
                task("free"),
            )

            val output = tool(repo).execute(ListTasksInput(selection = TaskSelection.BLOCKED))

            assertEquals(listOf("waiting"), titles(output))
        }

    @Test
    fun `narrowing is an intersection`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.seed(
                task("both", tags = listOf(TagId("home"), TagId("errand")), projectId = ProjectId("p1")),
                task("one-tag", tags = listOf(TagId("home")), projectId = ProjectId("p1")),
                task("other-project", tags = listOf(TagId("home"), TagId("errand")), projectId = ProjectId("p2")),
            )

            val output =
                tool(repo).execute(
                    ListTasksInput(
                        selection = TaskSelection.ACTIVE_NOT_DEFERRED,
                        projectId = "p1",
                        tagIds = listOf("home", "errand"),
                    ),
                )

            assertEquals(listOf("both"), titles(output))
        }

    @Test
    fun `a priority the model writes in lower case still matches`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.seed(
                task("high", priority = TaskPriority.High),
                task("low", priority = TaskPriority.Low),
            )

            val output =
                tool(repo).execute(
                    ListTasksInput(selection = TaskSelection.ACTIVE_NOT_DEFERRED, priorities = listOf("high")),
                )

            assertEquals(listOf("high"), titles(output))
        }

    @Test
    fun `a cursor continues the page it came from`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.seed(
                task("a", dueDate = LocalDate(2026, 3, 11)),
                task("b", dueDate = LocalDate(2026, 3, 12)),
                task("c", dueDate = LocalDate(2026, 3, 13)),
            )
            val tool = tool(repo)

            val first = read(tool.execute(ListTasksInput(selection = TaskSelection.ACTIVE_NOT_DEFERRED, limit = 2)))
            assertEquals(listOf("a", "b"), titlesOf(first))
            val cursor = first["nextCursor"]!!.jsonPrimitive.content

            val second =
                read(
                    tool.execute(
                        ListTasksInput(selection = TaskSelection.ACTIVE_NOT_DEFERRED, limit = 2, cursor = cursor),
                    ),
                )
            assertEquals(listOf("c"), titlesOf(second))
            val next = second["nextCursor"]
            assertTrue(next == null || next is JsonNull, "the last page has no cursor")
        }

    @Test
    fun `a cursor from another selection is refused rather than used`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.seed(
                task("a", dueDate = LocalDate(2026, 3, 11)),
                task("b", dueDate = LocalDate(2026, 3, 12)),
                task("c", dueDate = LocalDate(2026, 3, 13)),
            )
            val tool = tool(repo)

            val first = read(tool.execute(ListTasksInput(selection = TaskSelection.ACTIVE_NOT_DEFERRED, limit = 1)))
            val cursor = first["nextCursor"]!!.jsonPrimitive.content

            val other =
                read(
                    tool.execute(ListTasksInput(selection = TaskSelection.UPCOMING, limit = 2, cursor = cursor)),
                )

            assertTrue(other["cursorWarning"] != null, "a cursor from another selection must be said out loud")
            assertEquals(2, other["tasks"]!!.jsonArray.size, "the read starts from the beginning")
        }

    @Test
    fun `the health of the selection is reported`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.seed(
                task("late", dueDate = LocalDate(2026, 3, 1)),
                task("waiting", blockedBy = setOf(TaskId("prerequisite"))),
                task("prerequisite"),
                task("fine"),
            )

            val output = read(tool(repo).execute(ListTasksInput(selection = TaskSelection.ACTIVE_NOT_DEFERRED)))
            val health = output["health"]!!.jsonObject

            assertEquals(1, health["overdue"]!!.jsonPrimitive.content.toInt())
            assertEquals(1, health["blocked"]!!.jsonPrimitive.content.toInt())
            assertEquals(0, health["stagnant"]!!.jsonPrimitive.content.toInt())
            assertEquals(4, health["totalOpen"]!!.jsonPrimitive.content.toInt())
        }

    @Test
    fun `a cursor that is not a cursor is refused rather than guessed at`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.seed(task("a"))

            val output =
                read(
                    tool(
                        repo,
                    ).execute(ListTasksInput(selection = TaskSelection.ACTIVE_NOT_DEFERRED, cursor = "not-a-cursor")),
                )

            assertTrue(output["cursorWarning"] != null)
            assertEquals(1, output["tasks"]!!.jsonArray.size)
        }

    @Test
    fun `the answer names the selection the way the wire does`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.seed(task("a"))
            val output = read(tool(repo).execute(ListTasksInput(selection = TaskSelection.ACTIVE_NOT_DEFERRED)))

            assertEquals(
                "active_not_deferred",
                output["selection"]!!.jsonPrimitive.content,
                "the reader is told which set it got, under the name the wire knows",
            )
        }

    @Test
    fun `the cursor names the selection it came from`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.seed(task("a"), task("b"))
            val tool = tool(repo)

            val first = read(tool.execute(ListTasksInput(selection = TaskSelection.ACTIVE_NOT_DEFERRED, limit = 1)))
            val cursor = first["nextCursor"]!!.jsonPrimitive.content
            val decoded = assertNotNull(cursor.decodeTaskPageCursor())

            assertTrue(decoded.fingerprint.isNotEmpty(), "a cursor says what it was reading")
            assertEquals(1, decoded.offset)
        }

    @Test
    fun `the staleness threshold is the user's own`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.seed(
                task(
                    "sitting",
                    updatedAt = Instant.parse("2026-03-01T00:00:00Z"),
                ),
            )
            val settings = FakeSettingsRepository()
            settings.setStalenessThresholdDays(7)

            val output = tool(repo, settings).execute(ListTasksInput(selection = TaskSelection.ACTIVE))
            val health =
                read(output)["tasks"]!!
                    .jsonArray
                    .first()
                    .jsonObject["health"]!!
                    .jsonPrimitive.content

            assertEquals("stagnant", health, "the user's own threshold decides")
        }

    @Test
    fun `a blocked and stale task of little value is called a burden`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.seed(
                task(
                    "waiting",
                    priority = TaskPriority.Low,
                    blockedBy = setOf(TaskId("prerequisite")),
                    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
                task("prerequisite", updatedAt = Instant.parse("2026-01-01T00:00:00Z")),
            )

            val output = tool(repo).execute(ListTasksInput(selection = TaskSelection.ACTIVE))
            val first = read(output)["tasks"]!!.jsonArray.first().jsonObject

            assertTrue(first["toxic"]!!.jsonPrimitive.content.toBoolean(), "it weighs on the user")
        }

    @Test
    fun `a task nobody prioritised is not called a burden`() =
        runTest {
            val repo = FakeTaskRepository()
            repo.seed(
                task(
                    "unprioritised",
                    priority = TaskPriority.None,
                    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )

            val output = tool(repo).execute(ListTasksInput(selection = TaskSelection.ACTIVE))
            val first = read(output)["tasks"]!!.jsonArray.first().jsonObject

            assertFalse(first["toxic"]!!.jsonPrimitive.content.toBoolean(), "not stated is not worthless")
        }

    // ─── The name on the wire ───────────────────────────────────────────────────

    /**
     * The rename has to be readable in both directions: an old caller keeps working,
     * and what the tool hands back is the new name. Both directions are pinned here
     * because an alias asserted only in a comment is an alias nobody will notice
     * breaking.
     */
    @Test
    fun `the old inbox name still decodes, and the selection encodes under its new name`() {
        assertEquals(
            TaskSelection.ACTIVE_NOT_DEFERRED,
            StableJson.decodeFromString<TaskSelection>("\"inbox\""),
            "a saved prompt or a caller that still sends `inbox` keeps working",
        )
        assertEquals(
            "\"active_not_deferred\"",
            StableJson.encodeToString(TaskSelection.ACTIVE_NOT_DEFERRED),
            "the alias is read-only: the tool speaks the new name",
        )
    }

    /**
     * The description is the only place the model learns the vocabulary, so it has to
     * offer exactly the names the tool decodes. A description that advertises a name the
     * enum does not accept — or that accepts a name the description does not offer —
     * sends the model into a decode failure it cannot diagnose.
     */
    @Test
    fun `the description offers exactly the names the tool decodes`() {
        val wireNames =
            TaskSelection.entries.map { TaskSelection.serializer().descriptor.getElementName(it.ordinal) }
        val offered =
            ListTasksTool.DESCRIPTION
                .substringAfter("The selection is one of: ")
                .substringBefore(". ")
                .split(", ")
                .map { it.trim() }

        assertEquals(wireNames, offered, "the description must name the wire values, in the enum's order")
        assertTrue(
            wireNames.containsAll(typeNamesIn(offered)),
            "every advertised value is a value the enum decodes",
        )
    }

    private fun typeNamesIn(offered: List<String>): List<String> =
        offered.filter {
            it.isNotEmpty() &&
                it.all { c -> c == '_' || c.isLowerCase() }
        }
}

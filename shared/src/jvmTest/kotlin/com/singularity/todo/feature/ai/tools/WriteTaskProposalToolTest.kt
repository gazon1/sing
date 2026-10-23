package com.singularity.todo.feature.ai.tools

import com.singularity.todo.feature.profile.ProfileAwareCurrentUser
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
import com.singularity.todo.feature.proposals.domain.model.TaskValue
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProposalRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/**
 * The write path, tested through the question it has to answer honestly.
 *
 * A tool that proposes is a tool that says "I did not change anything, I asked". The
 * two failure modes are the same in shape and opposite in direction: a proposal that
 * changes the task anyway, and a proposal that is reported as a change when it is
 * not. Both are checked here.
 */
@Tag("fast")
class WriteTaskProposalToolTest {

    private val clock = FakeClock(Instant.parse("2026-03-10T09:00:00Z"))

    private fun task(id: String = "task-1"): Task = Task(
        id = TaskId(id),
        title = "Task $id",
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        userId = TestUsers.DEFAULT,
    )

    private fun currentUser(): ProfileAwareCurrentUser = FakeProfileAwareCurrentUser()

    private fun output(raw: String): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `a proposed change is proposed, and the task is not changed`() = runTest {
        val tasks = FakeTaskRepository()
        tasks.seed(task())
        val proposals = FakeProposalRepository(clock)
        val tool = WriteTaskProposalTool(proposals, tasks, currentUser(), clock)

        val result = output(
            tool.execute(
                WriteTaskProposalInput(
                    taskId = "task-1",
                    operation = "set_due_date",
                    value = "2026-04-01",
                    reason = "the deadline moved",
                ),
            ),
        )

        assertTrue(result["proposalCreated"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(result["proposalCreated"]!!.jsonPrimitive.content == "false")

        // The task itself is untouched: the change waits for the user.
        val stored = tasks.get(TaskId("task-1"))
        assertNotNull(stored)
        assertEquals(null, stored.dueDate, "a proposal does not change the task")

        // And there is a proposal waiting for a decision.
        val pending = proposals.watchProposalsByStatus(ProposalStatus.Pending).first()
        assertEquals(1, pending.size)
        assertEquals(ProposalSource.Agent, pending.single().source)
        assertEquals(
            ProposalItemKind.SetTaskField(TaskValue.DueDate(LocalDate.parse("2026-04-01"))),
            pending.single().items.single().kind,
        )
    }

    @Test
    fun `the answer says proposed, and carries the line the user reads`() = runTest {
        val tasks = FakeTaskRepository()
        tasks.seed(task())
        val tool = WriteTaskProposalTool(FakeProposalRepository(clock), tasks, currentUser(), clock)

        val result = output(
            tool.execute(
                WriteTaskProposalInput(
                    taskId = "task-1",
                    operation = "set_title",
                    value = "New title",
                    reason = "the old title was wrong",
                ),
            ),
        )

        val summary = result["summary"]!!.jsonPrimitive.content
        assertTrue(summary.contains("title"), "the summary names the change: $summary")
        assertTrue(summary.contains("the old title was wrong"), "the summary carries the reason: $summary")
    }

    @Test
    fun `an operation that is not one of ours is refused, and the answer says which are`() = runTest {
        val tasks = FakeTaskRepository()
        tasks.seed(task())
        val proposals = FakeProposalRepository(clock)
        val tool = WriteTaskProposalTool(proposals, tasks, currentUser(), clock)

        val result = output(
            tool.execute(WriteTaskProposalInput(taskId = "task-1", operation = "set_colour", value = "red")),
        )

        assertFalse(result["proposalCreated"]!!.jsonPrimitive.content.toBoolean())
        val error = result["error"]!!.jsonPrimitive.content
        assertTrue(error.contains("set_title"), "the answer lists the operations: $error")
        assertTrue(proposals.watchProposalsByStatus(ProposalStatus.Pending).first().isEmpty())
    }

    @Test
    fun `a missing value is refused`() = runTest {
        val tasks = FakeTaskRepository()
        tasks.seed(task())
        val proposals = FakeProposalRepository(clock)
        val tool = WriteTaskProposalTool(proposals, tasks, currentUser(), clock)

        val result = output(
            tool.execute(WriteTaskProposalInput(taskId = "task-1", operation = "set_title")),
        )

        assertFalse(result["proposalCreated"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("value"))
        assertTrue(proposals.watchProposalsByStatus(ProposalStatus.Pending).first().isEmpty())
    }

    @Test
    fun `a missing item is refused`() = runTest {
        val tasks = FakeTaskRepository()
        tasks.seed(task())
        val proposals = FakeProposalRepository(clock)
        val tool = WriteTaskProposalTool(proposals, tasks, currentUser(), clock)

        val result = output(
            tool.execute(WriteTaskProposalInput(taskId = "task-1", operation = "add_tags")),
        )

        assertFalse(result["proposalCreated"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("item"))
        assertTrue(proposals.watchProposalsByStatus(ProposalStatus.Pending).first().isEmpty())
    }

    @Test
    fun `a task that does not exist is refused`() = runTest {
        val tasks = FakeTaskRepository()
        val proposals = FakeProposalRepository(clock)
        val tool = WriteTaskProposalTool(proposals, tasks, currentUser(), clock)

        val result = output(
            tool.execute(
                WriteTaskProposalInput(taskId = "nope", operation = "set_title", value = "x"),
            ),
        )

        assertFalse(result["proposalCreated"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result["error"]!!.jsonPrimitive.content.contains("no task"))
        assertTrue(proposals.watchProposalsByStatus(ProposalStatus.Pending).first().isEmpty())
    }
}

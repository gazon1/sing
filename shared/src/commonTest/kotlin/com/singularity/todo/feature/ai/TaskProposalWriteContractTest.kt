@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.ai.tools.DecomposeAndCreateInput
import com.singularity.todo.feature.ai.tools.DecomposeAndCreateOutput
import com.singularity.todo.feature.ai.tools.DecomposeAndCreateTool
import com.singularity.todo.feature.ai.tools.FakePlanExecutor
import com.singularity.todo.feature.ai.tools.UpdateTaskInput
import com.singularity.todo.feature.ai.tools.UpdateTaskOutput
import com.singularity.todo.feature.ai.tools.UpdateTaskTool
import com.singularity.todo.feature.ai.tools.WriteTaskProposalInput
import com.singularity.todo.feature.ai.tools.WriteTaskProposalOutput
import com.singularity.todo.feature.ai.tools.WriteTaskProposalTool
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.ProposalDispatch
import com.singularity.todo.feature.proposals.domain.usecase.ProposalPlanner
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.test.fakes.FakeChecklistRepository
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeNotesRepository
import com.singularity.todo.test.fakes.FakeProfileAwareCurrentUser
import com.singularity.todo.test.fakes.FakeProjectsRepository
import com.singularity.todo.test.fakes.FakeProposalRepository
import com.singularity.todo.test.fakes.FakeTagsRepository
import com.singularity.todo.test.fakes.FakeTaskRepository
import com.singularity.todo.test.fakes.FakeTimeTrackingRepository
import com.singularity.todo.test.fakes.TestUsers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The write contract of the task tools that propose instead of writing.
 *
 * ## Why the refusal is the case that matters here
 *
 * A tool that is classified as proposal-gated but writes on its own would break the
 * promise the whole surface makes: *the user's data changes only after the user says
 * yes*. The write is the easy half to see; the refusal is the half that gets
 * forgotten — a tool that proposes and then writes anyway passes every test that
 * only looks at the confirmation path.
 *
 * So this test drives the whole round trip and asserts on the state of the user's
 * data after each step — not on the tool's own report of what it did.
 *
 * ## The three tools
 *
 * - [UpdateTaskTool] — the model names the fields to change, and the tool reads the
 *   task first so it can propose only what really differs.
 * - [WriteTaskProposalTool] — the model names the change as an operation, and the
 *   tool holds exactly one change.
 * - [DecomposeAndCreateTool] — the model asks a language model for a plan and the
 *   tool proposes every sub-task of that plan at once.
 *
 * All three are read back from the repository, so a tool that lies about what it did
 * fails here rather than in a user's list.
 */
@Tag("fast")
class TaskProposalWriteContractTest {

    private val clock = FakeClock()
    private val user: UserId = TestUsers.DEFAULT

    private val proposals = FakeProposalRepository(clock)
    private val tasks = FakeTaskRepository()
    private val tags = FakeTagsRepository()
    private val checklist = FakeChecklistRepository()
    private val timeTracking = FakeTimeTrackingRepository(clock)
    private val notes = FakeNotesRepository()
    private val projectsFake = FakeProjectsRepository(FakeProfileAwareCurrentUser(user))
    private val deleteProject = DeleteProjectUseCase(projectsFake, tasks)

    private val currentUser = FakeProfileAwareCurrentUser(user)

    private val apply = ApplyProposalItemUseCase(
        proposals = proposals,
        tasks = tasks,
        notes = notes,
        projects = projectsFake,
        tags = tags,
        planner = ProposalPlanner(clock),
        dispatch = ProposalDispatch(tasks, tags, checklist, timeTracking, notes, projectsFake, deleteProject, clock),
    )

    // ── The catalogue and the behaviour must agree ──────────────────────────────

    @Test
    fun `the catalogue classifies the tools this test drives as proposal-gated`() {
        listOf("update_task", "write_task_proposal", "decompose_and_create").forEach { name ->
            val entry = McpToolCatalog.catalog.single { it.name == name }
            assertEquals(
                WriteBehavior.PROPOSAL_WRITE,
                entry.writeBehavior,
                "$name is a proposal-gated tool",
            )
        }
    }

    // ── update_task: propose, confirm, write ───────────────────────────────────

    @Test
    fun `update_task proposes a change and writes nothing`() = runTest {
        val task = seedTask("Buy groceries")

        val output = decodeUpdate(
            UpdateTaskTool(proposals, tasks, currentUser, clock)
                .execute(UpdateTaskInput(taskId = task.id.value, title = "Buy milk")),
        )

        assertTrue(output.proposalCreated, "the tool reported a proposal: ${output.error}")
        assertTrue(output.proposalId.isNotBlank(), "the proposal is named, so the user can find it")
        assertEquals(
            "Buy groceries",
            tasks.tasks.value[task.id.value]?.title,
            "the task was not touched by the call",
        )

        val stored = proposals.watchProposal(ProposalId(output.proposalId)).first()
        assertNotNull(stored, "the proposal is in the store")
        assertEquals(1, stored.items.size, "one field changed, so one change is proposed")
        assertEquals(ProposalItemStatus.Pending, stored.items.first().status, "the change waits for the user")
    }

    @Test
    fun `update_task refuses a task that does not exist and proposes nothing`() = runTest {
        val output = decodeUpdate(
            UpdateTaskTool(proposals, tasks, currentUser, clock)
                .execute(UpdateTaskInput(taskId = "no-such-task", title = "Buy milk")),
        )

        assertFalse(output.proposalCreated, "no proposal was made: ${output.error}")
        assertNotNull(output.error, "the model is told why nothing was proposed")
        assertTrue(output.proposalId.isBlank(), "there is no proposal to find")
        assertTrue(tasks.tasks.value.isEmpty(), "nothing was written: ${tasks.tasks.value.keys}")
    }

    @Test
    fun `confirming an update_task proposal writes the change`() = runTest {
        val task = seedTask("Buy groceries")

        val output = decodeUpdate(
            UpdateTaskTool(proposals, tasks, currentUser, clock)
                .execute(UpdateTaskInput(taskId = task.id.value, title = "Buy milk")),
        )
        val result = apply.confirm(singleItemId(output.proposalId), user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")

        assertEquals(
            "Buy milk",
            tasks.tasks.value[task.id.value]?.title,
            "the confirmed change is in the user's task",
        )
    }

    @Test
    fun `declining an update_task proposal leaves the task unchanged`() = runTest {
        val task = seedTask("Buy groceries")

        val output = decodeUpdate(
            UpdateTaskTool(proposals, tasks, currentUser, clock)
                .execute(UpdateTaskInput(taskId = task.id.value, title = "Buy milk")),
        )
        val itemId = singleItemId(output.proposalId)
        val result = apply.reject(itemId, user, reason = "not what I meant")
        assertTrue(result.isSuccess, "the refusal succeeded: ${result.exceptionOrNull()?.message}")

        assertEquals(
            "Buy groceries",
            tasks.tasks.value[task.id.value]?.title,
            "the task is exactly as it was",
        )
        assertEquals(
            ProposalItemStatus.Rejected,
            proposals.getItem(itemId)?.status,
            "the change was refused, not left pending",
        )
    }

    // ── write_task_proposal: one named operation, held until the user says yes ──

    @Test
    fun `write_task_proposal proposes a change and writes nothing`() = runTest {
        val task = seedTask("Buy groceries")

        val output = decodeWrite(
            WriteTaskProposalTool(proposals, tasks, currentUser, clock).execute(
                WriteTaskProposalInput(
                    taskId = task.id.value,
                    operation = "set_title",
                    value = "Buy milk",
                    reason = "the list changed",
                ),
            ),
        )

        assertTrue(output.proposalCreated, "the tool reported a proposal: ${output.error}")
        assertTrue(output.proposalId.isNotBlank(), "the proposal is named, so the user can find it")
        assertTrue(
            output.summary.contains("Buy milk"),
            "the summary names the value, so the user approves what they read: ${output.summary}",
        )
        assertEquals(
            "Buy groceries",
            tasks.tasks.value[task.id.value]?.title,
            "the task was not touched by the call",
        )

        val stored = proposals.watchProposal(ProposalId(output.proposalId)).first()
        assertNotNull(stored, "the proposal is in the store")
        assertEquals(1, stored.items.size, "one operation, one change")
        assertEquals(ProposalItemStatus.Pending, stored.items.first().status, "the change waits for the user")
    }

    @Test
    fun `confirming a write_task_proposal proposal writes the change`() = runTest {
        val task = seedTask("Buy groceries")

        val output = decodeWrite(
            WriteTaskProposalTool(proposals, tasks, currentUser, clock).execute(
                WriteTaskProposalInput(taskId = task.id.value, operation = "set_title", value = "Buy milk"),
            ),
        )
        val result = apply.confirm(singleItemId(output.proposalId), user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")

        assertEquals(
            "Buy milk",
            tasks.tasks.value[task.id.value]?.title,
            "the confirmed change is in the user's task",
        )
    }

    @Test
    fun `declining a write_task_proposal proposal leaves the task unchanged`() = runTest {
        val task = seedTask("Buy groceries")

        val output = decodeWrite(
            WriteTaskProposalTool(proposals, tasks, currentUser, clock).execute(
                WriteTaskProposalInput(taskId = task.id.value, operation = "set_title", value = "Buy milk"),
            ),
        )
        val itemId = singleItemId(output.proposalId)
        val result = apply.reject(itemId, user, reason = "not now")
        assertTrue(result.isSuccess, "the refusal succeeded: ${result.exceptionOrNull()?.message}")

        assertEquals(
            "Buy groceries",
            tasks.tasks.value[task.id.value]?.title,
            "the task is exactly as it was",
        )
        assertEquals(
            ProposalItemStatus.Rejected,
            proposals.getItem(itemId)?.status,
            "the change was refused, not left pending",
        )
    }

    // ── decompose_and_create: a plan from the model, held like any other change ─

    @Test
    fun `decompose_and_create proposes the plan and creates nothing`() = runTest {
        val output = decodeDecompose(
            decomposeTool(FakePlanExecutor(PLAN)).execute(DecomposeAndCreateInput(title = "Buy groceries")),
        )

        assertTrue(output.proposalCreated, "the tool reported a proposal: ${output.error}")
        assertTrue(output.proposalId.isNotBlank(), "the proposal is named, so the user can find it")
        assertEquals("llm", output.planSource, "the plan came from the model")
        assertEquals(2, output.proposedTaskCount, "the plan holds two sub-tasks")
        assertTrue(tasks.tasks.value.isEmpty(), "nothing was created: ${tasks.tasks.value.keys}")

        val stored = proposals.watchProposal(ProposalId(output.proposalId)).first()
        assertNotNull(stored, "the proposal is in the store")
        assertEquals(2, stored.items.size, "one change per sub-task")
        assertTrue(
            stored.items.all { it.status == ProposalItemStatus.Pending },
            "every sub-task waits for the user",
        )
    }

    @Test
    fun `confirming a decompose_and_create proposal creates the planned tasks`() = runTest {
        val output = decodeDecompose(
            decomposeTool(FakePlanExecutor(PLAN)).execute(DecomposeAndCreateInput(title = "Buy groceries")),
        )

        itemIds(output.proposalId).forEach { itemId ->
            val result = apply.confirm(itemId, user)
            assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")
        }

        val titles = tasks.tasks.value.values.map { it.title }.sorted()
        assertEquals(listOf("Buy bread", "Buy milk"), titles, "the planned tasks are in the user's list")
        assertTrue(
            tasks.tasks.value.values.all { it.userId == user },
            "the tasks belong to the user who confirmed them",
        )
    }

    @Test
    fun `declining a decompose_and_create proposal creates nothing`() = runTest {
        val output = decodeDecompose(
            decomposeTool(FakePlanExecutor(PLAN)).execute(DecomposeAndCreateInput(title = "Buy groceries")),
        )

        val ids = itemIds(output.proposalId)
        ids.forEach { itemId ->
            val result = apply.reject(itemId, user, reason = "too many tasks")
            assertTrue(result.isSuccess, "the refusal succeeded: ${result.exceptionOrNull()?.message}")
        }

        assertTrue(tasks.tasks.value.isEmpty(), "nothing was created: ${tasks.tasks.value.keys}")
        assertTrue(
            ids.all { proposals.getItem(it)?.status == ProposalItemStatus.Rejected },
            "every sub-task was refused, not left pending",
        )
    }

    @Test
    fun `decompose_and_create proposes nothing when the model cannot be reached`() = runTest {
        val broken = FakePlanExecutor(answer = "", failure = IllegalStateException("no api key"))

        val output = decodeDecompose(
            decomposeTool(broken).execute(DecomposeAndCreateInput(title = "Buy groceries")),
        )

        assertFalse(output.proposalCreated, "no proposal was made: ${output.error}")
        assertEquals("empty", output.planSource, "the tool says where the plan did not come from")
        assertEquals(0, output.proposedTaskCount, "there is no plan, so there is nothing to propose")
        assertTrue(tasks.tasks.value.isEmpty(), "nothing was created: ${tasks.tasks.value.keys}")
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────

    /** The tool under test, wired to the executor the test hands it. */
    private fun decomposeTool(executor: FakePlanExecutor) =
        DecomposeAndCreateTool(proposals, clock, executor, FakePlanExecutor.TEST_MODEL, currentUser)

    private fun seedTask(title: String): Task {
        val task = Task(
            id = TaskId.generate(),
            title = title,
            description = null,
            priority = TaskPriority.None,
            kind = TaskKind.Task,
            createdAt = clock.now(),
            updatedAt = clock.now(),
            userId = user,
        )
        tasks.seed(task)
        return task
    }

    private fun decodeUpdate(raw: String): UpdateTaskOutput = Json.decodeFromString(UpdateTaskOutput.serializer(), raw)

    private fun decodeWrite(raw: String): WriteTaskProposalOutput =
        Json.decodeFromString(WriteTaskProposalOutput.serializer(), raw)

    private fun decodeDecompose(raw: String): DecomposeAndCreateOutput =
        Json.decodeFromString(DecomposeAndCreateOutput.serializer(), raw)

    private suspend fun singleItemId(proposalId: String) =
        requireNotNull(proposals.watchProposal(ProposalId(proposalId)).first()?.items?.singleOrNull()).id

    private suspend fun itemIds(proposalId: String): List<ProposalItemId> =
        requireNotNull(proposals.watchProposal(ProposalId(proposalId)).first()?.items).map { it.id }

    private companion object {
        /** The plan the fake model answers with — two sub-tasks of one task. */
        const val PLAN = """{"subTasks":["Buy milk","Buy bread"]}"""
    }
}

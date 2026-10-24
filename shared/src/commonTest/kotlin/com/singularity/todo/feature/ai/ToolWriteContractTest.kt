@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.ai

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.ai.tools.CreateTaskInput
import com.singularity.todo.feature.ai.tools.CreateTaskOutput
import com.singularity.todo.feature.ai.tools.CreateTaskTool
import com.singularity.todo.feature.ai.tools.DeleteTaskInput
import com.singularity.todo.feature.ai.tools.DeleteTaskOutput
import com.singularity.todo.feature.ai.tools.DeleteTaskTool
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
 * The write contract of a proposal-gated tool: the call proposes, the
 * confirmation writes, and **the refusal changes nothing**.
 *
 * ## Why the refusal is the case that matters here
 *
 * The catalogue says which tools write directly and which ones go through the
 * proposal pipeline. A tool that is classified as proposal-gated but writes on
 * its own would break the promise the whole surface makes: *the user's data
 * changes only after the user says yes*. The write is the easy half to see;
 * the refusal is the half that gets forgotten — a tool that proposes and then
 * writes anyway, or that deletes the row it proposed to delete, passes every
 * test that only looks at the confirmation path.
 *
 * So this test drives the whole round trip and asserts on the state of the
 * user's data after each step — not on the tool's own report of what it did.
 *
 * ## The two representatives
 *
 * - [CreateTaskTool] — a creation: the thing does not exist until the user says yes.
 * - [DeleteTaskTool] — a destructive change: the thing must still be there when
 *   the user says no.
 *
 * Both are read back from the repository, so a tool that lies about what it did
 * fails here rather than in a user's list.
 */
@Tag("fast")
class ToolWriteContractTest {

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
        val create = McpToolCatalog.catalog.single { it.name == "create_task" }
        assertEquals(
            WriteBehavior.PROPOSAL_WRITE,
            create.writeBehavior,
            "create_task is a proposal-gated tool",
        )

        val delete = McpToolCatalog.catalog.single { it.name == "delete_task" }
        assertEquals(
            WriteBehavior.PROPOSAL_WRITE,
            delete.writeBehavior,
            "delete_task is a proposal-gated tool",
        )
    }

    // ── The creation: propose, confirm, write ──────────────────────────────────

    @Test
    fun `calling a proposal-gated creation tool creates a proposal and writes nothing`() = runTest {
        val output = decodeCreate(
            CreateTaskTool(proposals, currentUser, clock).execute(CreateTaskInput(title = "Buy groceries")),
        )

        assertTrue(output.proposalCreated, "the tool reported a proposal: ${output.error}")
        assertTrue(output.proposalId.isNotBlank(), "the proposal is named, so the user can find it")
        assertTrue(tasks.tasks.value.isEmpty(), "nothing was written: ${tasks.tasks.value.keys}")

        val stored = proposals.watchProposal(ProposalId(output.proposalId)).first()
        assertNotNull(stored, "the proposal is in the store")
        assertEquals(1, stored.items.size, "the proposal holds one change")
        assertEquals(ProposalItemStatus.Pending, stored.items.first().status, "the change waits for the user")
    }

    @Test
    fun `confirming the proposal writes the task`() = runTest {
        val output = decodeCreate(
            CreateTaskTool(proposals, currentUser, clock).execute(CreateTaskInput(title = "Buy groceries")),
        )
        val itemId = singleItemId(output.proposalId)

        val result = apply.confirm(itemId, user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")

        val created = tasks.tasks.value.values.singleOrNull()
        assertNotNull(created, "the task exists")
        assertEquals("Buy groceries", created.title)
        assertEquals(user, created.userId)
    }

    @Test
    fun `declining the proposal leaves the data unchanged`() = runTest {
        val output = decodeCreate(
            CreateTaskTool(proposals, currentUser, clock).execute(CreateTaskInput(title = "Buy groceries")),
        )
        val itemId = singleItemId(output.proposalId)

        val result = apply.reject(itemId, user, reason = "not now")
        assertTrue(result.isSuccess, "the refusal succeeded: ${result.exceptionOrNull()?.message}")

        assertTrue(tasks.tasks.value.isEmpty(), "nothing was written: ${tasks.tasks.value.keys}")
        assertEquals(
            ProposalItemStatus.Rejected,
            proposals.getItem(itemId)?.status,
            "the change was refused, not left pending",
        )
    }

    // ── The destructive change: propose, decline, the thing is still there ─────

    @Test
    fun `declining a deletion proposal leaves the task in place`() = runTest {
        val task = Task(
            id = TaskId.generate(),
            title = "Buy groceries",
            description = null,
            priority = TaskPriority.None,
            kind = TaskKind.Task,
            createdAt = clock.now(),
            updatedAt = clock.now(),
            userId = user,
        )
        tasks.seed(task)

        val output = decodeDelete(
            DeleteTaskTool(proposals, currentUser, clock).execute(DeleteTaskInput(taskId = task.id.value)),
        )
        assertTrue(output.proposalCreated, "the tool reported a proposal: ${output.error}")
        assertNotNull(tasks.tasks.value[task.id.value], "the task is still there")

        val result = apply.reject(singleItemId(output.proposalId), user, reason = "keep it")
        assertTrue(result.isSuccess, "the refusal succeeded: ${result.exceptionOrNull()?.message}")

        val survivor = tasks.tasks.value[task.id.value]
        assertNotNull(survivor, "the task survived the refusal")
        assertFalse(survivor.isTrashed, "the task was not archived")
    }

    /**
     * The other half of the deletion's round trip.
     *
     * The refusal half says the task stays; this half says it goes — and it is
     * the half that can be wrong on its own. The tool proposes a change to a
     * *kind* of thing; if the proposal carried the wrong kind, or the wrong
     * target, then confirming it would do nothing at all, and every test that
     * only looks at the refusal would stay green. A deletion that does not
     * happen is a task the user asked to lose and still has.
     */
    @Test
    fun `confirming a deletion proposal archives the task`() = runTest {
        val task = Task(
            id = TaskId.generate(),
            title = "Buy groceries",
            description = null,
            priority = TaskPriority.None,
            kind = TaskKind.Task,
            createdAt = clock.now(),
            updatedAt = clock.now(),
            userId = user,
        )
        tasks.seed(task)

        val output = decodeDelete(
            DeleteTaskTool(proposals, currentUser, clock).execute(DeleteTaskInput(taskId = task.id.value)),
        )
        val result = apply.confirm(singleItemId(output.proposalId), user)
        assertTrue(result.isSuccess, "the confirmation succeeded: ${result.exceptionOrNull()?.message}")

        val archived = assertNotNull(tasks.tasks.value[task.id.value], "the task row is still readable")
        assertTrue(archived.isTrashed, "the confirmed deletion archived the task")
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────

    private fun decodeCreate(raw: String): CreateTaskOutput = Json.decodeFromString(CreateTaskOutput.serializer(), raw)

    private fun decodeDelete(raw: String): DeleteTaskOutput = Json.decodeFromString(DeleteTaskOutput.serializer(), raw)

    private suspend fun singleItemId(proposalId: String) =
        requireNotNull(proposals.watchProposal(ProposalId(proposalId)).first()?.items?.singleOrNull()).id
}

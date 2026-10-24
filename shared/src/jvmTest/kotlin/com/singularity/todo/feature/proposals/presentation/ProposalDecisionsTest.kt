@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.proposals.presentation

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.model.ProposalTargetKind
import com.singularity.todo.feature.proposals.domain.model.TaskValue
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.EditProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.ProposalDispatch
import com.singularity.todo.feature.proposals.domain.usecase.ProposalPlanner
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
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
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The five operations every proposal surface performs.
 *
 * The surface-specific parts — the wording and the way a failure is reported —
 * belong to the surface. The reasons are the decisions' own: one store, whatever
 * the surface, and a surface shows them by reading them. What is checked here is
 * that the shared operations keep the rules they promise: a failed change keeps
 * its own reason next to it, a change that worked has its reason cleared, and a
 * batch where some changes failed is not an error.
 */
@Tag("fast")
class ProposalDecisionsTest {

    private val clock = FakeClock()
    private val user = TestUsers.DEFAULT
    private val taskId = TaskId("t1")
    private val proposalId = ProposalId.fromString("prop-1")

    private val proposals = FakeProposalRepository(clock)
    private val tasks = FakeTaskRepository()
    private val tags = FakeTagsRepository()
    private val checklist = FakeChecklistRepository()
    private val timeTracking = FakeTimeTrackingRepository(clock)
    private val notes = FakeNotesRepository()
    private val projects: ProjectsRepository = FakeProjectsRepository(FakeProfileAwareCurrentUser(user))
    private val deleteProject = DeleteProjectUseCase(projects, tasks)

    private val useCase = ApplyProposalItemUseCase(
        proposals = proposals,
        tasks = tasks,
        notes = notes,
        projects = projects,
        tags = tags,
        planner = ProposalPlanner(clock),
        dispatch = ProposalDispatch(tasks, tags, checklist, timeTracking, notes, projects, deleteProject, clock),
    )

    private val errors = RecordingErrorSink()

    /** The wording of a surface that says what it does. */
    private val reviewStyle = ProposalDecisionStyle(
        apply = "Apply the change",
        reject = "Reject the change",
        applyAll = "Apply the changes",
        applyOne = "Apply the change",
        dismiss = "Dismiss the proposal",
        dismissOne = null,
        change = "Change the proposal",
    )

    @Test
    fun `a change that works has its reason forgotten`() = runTest {
        val decisions = newDecisions()
        val item = seedProposal()
        decisions.recordFailure(item.id, "an earlier failure")

        decisions.applyChange(item.id)

        assertNull(decisions.failures.value[item.id], "the change worked: there is nothing left to explain")
        assertEquals(
            ProposalItemStatus.Confirmed,
            storedItem(item.id).status,
            "the change is applied",
        )
    }

    @Test
    fun `a change that cannot be applied keeps the reason next to it`() = runTest {
        val item = seedProposal(seedTask = false)

        val decisions = newDecisions()
        decisions.applyChange(item.id)

        val reason = decisions.failures.value[item.id]
        assertNotNull(reason, "the reason is shown next to the change that failed")
        assertTrue(reason.contains("not found"), "the reason says what was wrong: $reason")
    }

    @Test
    fun `a failure is reported to the user`() = runTest {
        val item = seedProposal(seedTask = false)

        val decisions = newDecisions()
        decisions.applyChange(item.id)

        assertEquals(1, errors.reports.size, "the user is told")
        assertEquals("Apply the change", errors.reports.first().label, "under the operation's name")
    }

    @Test
    fun `the reason the user typed is stored with the refusal`() = runTest {
        val item = seedProposal()

        val decisions = newDecisions()
        decisions.turnDownChange(item.id, "the priority is right already")

        assertEquals(
            "the priority is right already",
            storedItem(item.id).rejectionReason,
            "the reason is what the model learns from",
        )
    }

    @Test
    fun `a surface that does not store the reason drops it`() = runTest {
        val item = seedProposal()

        val decisions = newDecisions(style = reviewStyle.copy(storesRejectionReason = false))
        decisions.turnDownChange(item.id, "the priority is right already")

        assertNull(storedItem(item.id).rejectionReason, "the reason is not stored")
    }

    @Test
    fun `a batch where some changes failed is not an error`() = runTest {
        val applied = seedProposal()
        val failed = seedItem("item-2", targetId = "t-missing")

        val decisions = newDecisions()
        decisions.applyAll(proposalId)

        assertTrue(errors.reports.isEmpty(), "a partly failed batch is a normal outcome")
        assertNull(decisions.failures.value[applied.id], "the change that worked has nothing to explain")
        assertNotNull(decisions.failures.value[failed.id], "the change that failed keeps its own reason")
    }

    @Test
    fun `a surface that reports a partly failed batch says how many changes failed`() = runTest {
        seedProposal()
        seedItem("item-2", targetId = "t-missing")

        val decisions = newDecisions(
            style = reviewStyle.copy(
                batchFailureText = { count -> "$count items could not be applied" },
            ),
        )
        decisions.applyAll(proposalId)

        assertEquals(1, errors.reports.size, "the user is told")
        assertEquals(
            "1 items could not be applied",
            errors.reports.first().message,
            "the message says how many changes it was",
        )
    }

    @Test
    fun `turning a whole proposal down turns every waiting change down`() = runTest {
        val item = seedProposal()

        val decisions = newDecisions()
        decisions.dismiss { listOf(item) }

        assertEquals(ProposalItemStatus.Rejected, storedItem(item.id).status, "the change is turned down")
    }

    @Test
    fun `a change that could not be turned down keeps its reason next to it`() = runTest {
        val item = seedProposal()
        val missing = item.copy(id = ProposalItemId.fromString("item-missing"))

        val decisions = newDecisions(style = reviewStyle.copy(dismissOne = "Turn the change down"))
        decisions.dismiss { listOf(missing) }

        assertNotNull(decisions.failures.value[missing.id], "the reason is shown next to the change")
    }

    @Test
    fun `a surface that keeps no reason for a dismissed change keeps none`() = runTest {
        val item = seedProposal()
        val missing = item.copy(id = ProposalItemId.fromString("item-missing"))

        val decisions = newDecisions()
        decisions.dismiss { listOf(missing) }

        assertTrue(decisions.failures.value.isEmpty(), "the proposal's own status says what became of it")
    }

    @Test
    fun `withdrawing a proposal takes its waiting changes with it`() = runTest {
        val item = seedProposal()

        val decisions = newDecisions(withdraw = { id, userId -> proposals.retract(id, userId) })
        decisions.withdraw(proposalId)

        assertEquals(ProposalItemStatus.Retracted, storedItem(item.id).status, "the change is withdrawn")
    }

    @Test
    fun `a withdrawal that fails is reported`() = runTest {
        seedProposal()

        val decisions = newDecisions(withdraw = { _, _ -> Result.failure(IllegalStateException("no such proposal")) })
        decisions.withdraw(proposalId)

        assertEquals(1, errors.reports.size, "the user is told")
        assertEquals("Dismiss the proposal", errors.reports.first().label, "under the operation's name")
    }

    @Test
    fun `a failure that arrives as a thrown exception is shown where the surface shows it`() = runTest {
        val decisions = newDecisions(withdraw = { _, _ -> throw IllegalStateException("boom") })
        decisions.withdraw(proposalId)

        assertEquals(1, errors.reports.size, "the user is told")
        assertEquals("Dismiss the proposal", errors.reports.first().label, "under the operation's name")
    }

    @Test
    fun `a value that does not read is refused with the reason`() = runTest {
        val item = seedProposal()

        val decisions = newDecisions()
        decisions.changeValue(item.id, "whenever")

        val reason = decisions.failures.value[item.id]
        assertNotNull(reason, "the reason is shown next to the change")
        assertTrue(reason.contains("whenever"), "the reason says what was wrong: $reason")
    }

    @Test
    fun `a surface without a write path decides nothing`() = runTest {
        val item = seedProposal()

        val decisions = newDecisions(apply = null)
        decisions.applyChange(item.id)

        assertTrue(decisions.failures.value.isEmpty(), "nothing failed")
        assertTrue(errors.reports.isEmpty(), "nothing was reported")
        assertEquals(ProposalItemStatus.Pending, storedItem(item.id).status, "nothing was decided")
    }

    /**
     * The shared operations, wired the way a surface wires them.
     */
    private fun newDecisions(
        style: ProposalDecisionStyle = reviewStyle,
        apply: ApplyProposalItemUseCase? = useCase,
        edit: EditProposalItemUseCase? = EditProposalItemUseCase(proposals),
        withdraw: (suspend (ProposalId, UserId) -> Result<Unit>)? = null,
    ) = ProposalDecisions(
        applyProposalItem = apply,
        editProposalItem = edit,
        style = style,
        errors = errors,
        userId = { user },
        withdrawProposal = withdraw,
    )

    /** Seeds the task the proposal is about, and a one-item proposal for it. */
    private suspend fun seedProposal(seedTask: Boolean = true): ProposalItem {
        if (seedTask) {
            tasks.seed(
                Task(
                    id = taskId,
                    title = "Original",
                    userId = user,
                    createdAt = clock.now(),
                    updatedAt = clock.now(),
                ),
            )
        }
        return seedItem("item-1", targetId = taskId.value)
    }

    /** Seeds one more change into the same proposal. */
    private suspend fun seedItem(itemId: String, targetId: String): ProposalItem {
        val item = ProposalItem(
            id = ProposalItemId.fromString(itemId),
            proposalId = proposalId,
            kind = ProposalItemKind.SetTaskField(TaskValue.Priority(TaskPriority.High)),
            targetId = targetId,
            humanSummary = "Set the priority to High",
            status = ProposalItemStatus.Pending,
            fingerprint = "set-priority-high-$itemId",
        )
        val existing = proposals.watchProposal(proposalId).first()
        proposals.save(
            AiProposal(
                id = proposalId,
                targetKind = ProposalTargetKind.Task,
                targetId = taskId.value,
                userId = user,
                source = ProposalSource.Detail,
                createdAt = clock.now(),
                updatedAt = clock.now(),
                items = (existing?.items.orEmpty() + item),
            ),
        )
        return item
    }

    /** The change as the repository holds it now. */
    private suspend fun storedItem(itemId: ProposalItemId): ProposalItem =
        requireNotNull(proposals.getItem(itemId)) { "the change $itemId is not stored" }

    /** A sink that keeps what it was told, where the test can read it. */
    private class RecordingErrorSink : ProposalErrorSink {
        val reports = mutableListOf<Report>()

        override suspend fun report(label: String, message: String, error: Throwable) {
            reports += Report(label, message, error)
        }

        /** One reported failure. */
        data class Report(val label: String, val message: String, val error: Throwable)
    }
}

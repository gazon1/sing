@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.proposals.presentation

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
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
import com.singularity.todo.test.helpers.TestVmContext
import com.singularity.todo.test.helpers.VmScope
import com.singularity.todo.test.helpers.testVm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The review screen's ViewModel.
 *
 * Two things have to be true: the screen shows what is waiting, and the user's word
 * reaches the app's own use case.
 */
@Tag("fast")
class ProposalsViewModelTest {

    private val clock = FakeClock()

    // The fakes scope every read to their own current user, which defaults to
    // TestUsers.DEFAULT. Fixtures must be stamped with the same id or they seed
    // successfully and are then invisible to the code under test.
    private val user = TestUsers.DEFAULT
    private val taskId = TaskId("t1")

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

    /**
     * The ViewModel under test.
     *
     * Its scope is a child of the background scope: the collector it starts never
     * ends, and a collector on the test's own scope would keep the test from
     * finishing. The work the intents start still runs on the test scheduler.
     */
    private fun TestScope.viewModel(
        events: MutableList<ProposalsUiEvent> = mutableListOf(),
    ): TestVmContext<ProposalsViewModel, ProposalsUiState> =
        testVm(
            stateAccessor = { vm: ProposalsViewModel -> vm.state },
            afterInit = { vm, bgScope ->
                bgScope.launch { vm.events.collect { events += it } }
            },
            vmScope = VmScope.Background,
        ) { scope ->
            ProposalsViewModel(
                proposals = proposals,
                applyProposalItem = useCase,
                editProposalItem = EditProposalItemUseCase(proposals),
                currentUser = FakeProfileAwareCurrentUser(user),
                scope = scope,
            )
        }

    @Test
    fun `the waiting changes are shown`() = runTest {
        seedProposal()

        val ctx = viewModel()

        val state = ctx.state.value
        assertEquals(1, state.proposals.size, "the proposal is there")
        assertEquals(1, state.proposals.first().items.size, "with its change")
        assertEquals("Set the priority to High", state.proposals.first().items.first().humanSummary)
    }

    @Test
    fun `the user applies a change`() = runTest {
        val itemId = seedProposal()

        val ctx = viewModel()

        ctx.act { onIntent(ProposalsIntent.Confirm(itemId)) }

        val items = proposals.watchProposal(ProposalId.fromString(proposalId)).first()!!.items
        assertEquals(ProposalItemStatus.Confirmed, items.first().status, "the change is applied")
    }

    @Test
    fun `the user turns a change down`() = runTest {
        val itemId = seedProposal()

        val ctx = viewModel()

        ctx.act { onIntent(ProposalsIntent.Reject(itemId)) }

        val items = proposals.watchProposal(ProposalId.fromString(proposalId)).first()!!.items
        assertEquals(ProposalItemStatus.Rejected, items.first().status, "nothing is applied")
    }

    @Test
    fun `the reason the user typed is kept with the refusal`() = runTest {
        val itemId = seedProposal()

        val ctx = viewModel()

        ctx.act { onIntent(ProposalsIntent.Reject(itemId, "the priority is right already")) }

        val items = proposals.watchProposal(ProposalId.fromString(proposalId)).first()!!.items
        assertEquals(
            "the priority is right already",
            items.first().rejectionReason,
            "the reason is what the model learns from",
        )
    }

    @Test
    fun `a decided change is no longer waiting`() = runTest {
        val itemId = seedProposal()

        val ctx = viewModel()

        ctx.act { onIntent(ProposalsIntent.Confirm(itemId)) }

        assertTrue(ctx.state.value.proposals.isEmpty(), "the decided change is gone")
    }

    @Test
    fun `the user accepts the whole proposal`() = runTest {
        seedProposal()

        val ctx = viewModel()

        ctx.act { onIntent(ProposalsIntent.ConfirmAll(ProposalId.fromString(proposalId))) }

        val items = proposals.watchProposal(ProposalId.fromString(proposalId)).first()!!.items
        assertEquals(ProposalItemStatus.Confirmed, items.first().status, "every change is applied")
    }

    @Test
    fun `a partly failed batch is not an error`() = runTest {
        val applied = seedProposal()
        val failed = seedItem("item-2", targetId = "t-missing")
        val events = mutableListOf<ProposalsUiEvent>()

        val ctx = viewModel(events)

        ctx.act { onIntent(ProposalsIntent.ConfirmAll(ProposalId.fromString(proposalId))) }

        assertTrue(events.isEmpty(), "a partly failed batch is a normal outcome: $events")
        assertNull(ctx.state.value.failures[applied], "the change that worked has nothing to explain")
        assertNotNull(ctx.state.value.failures[failed.id], "the change that failed keeps its own reason")
    }

    @Test
    fun `the user dismisses the whole proposal`() = runTest {
        seedProposal()

        val ctx = viewModel()

        ctx.act { onIntent(ProposalsIntent.Dismiss(ProposalId.fromString(proposalId))) }

        val items = proposals.watchProposal(ProposalId.fromString(proposalId)).first()!!.items
        assertEquals(ProposalItemStatus.Rejected, items.first().status, "every change is turned down")
    }

    @Test
    fun `the user changes the value of a change before applying it`() = runTest {
        val itemId = seedProposal()

        val ctx = viewModel()

        ctx.act { onIntent(ProposalsIntent.Edit(itemId, "medium")) }

        val items = proposals.watchProposal(ProposalId.fromString(proposalId)).first()!!.items
        val kind = items.first().kind
        assertEquals(
            ProposalItemKind.SetTaskField(TaskValue.Priority(TaskPriority.Medium)),
            kind,
            "the value is the one the user typed",
        )
        assertEquals(
            "Set the priority to Medium",
            items.first().humanSummary,
            "the summary follows the value — it is what the user reads",
        )
    }

    @Test
    fun `a value that does not read is refused with the reason`() = runTest {
        val itemId = seedProposal()

        val ctx = viewModel()

        ctx.act { onIntent(ProposalsIntent.Edit(itemId, "whenever")) }

        val reason = ctx.state.value.failures[itemId]
        assertNotNull(reason, "the reason is shown next to the change")
        assertTrue(reason.contains("whenever"), "the reason says what was wrong: $reason")

        val items = proposals.watchProposal(ProposalId.fromString(proposalId)).first()!!.items
        assertEquals(
            ProposalItemKind.SetTaskField(TaskValue.Priority(TaskPriority.High)),
            items.first().kind,
            "the change keeps the value it had",
        )
    }

    @Test
    fun `a change that cannot be applied keeps the reason next to it`() = runTest {
        val itemId = seedProposal(seedTask = false)

        val ctx = viewModel()

        ctx.act { onIntent(ProposalsIntent.Confirm(itemId)) }

        val reason = ctx.state.value.failures[itemId]
        assertNotNull(reason, "the reason is shown next to the change that failed")
    }

    @Test
    fun `a change that worked has its reason cleared`() = runTest {
        val itemId = seedProposal()

        val ctx = viewModel()

        // A refused value leaves its reason behind.
        ctx.act { onIntent(ProposalsIntent.Edit(itemId, "whenever")) }
        assertNotNull(ctx.state.value.failures[itemId], "the refusal left its reason")

        // A change that goes through takes it away.
        ctx.act { onIntent(ProposalsIntent.Confirm(itemId)) }
        assertNull(ctx.state.value.failures[itemId], "there is nothing left to explain")
    }

    private val proposalId = "prop-1"

    /** Seeds the task the proposal is about, and a one-item proposal for it. */
    private suspend fun seedProposal(seedTask: Boolean = true): ProposalItemId {
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
        return seedItem("item-1", targetId = taskId.value).id
    }

    /** Seeds one more change into the same proposal. */
    private suspend fun seedItem(itemId: String, targetId: String): ProposalItem {
        val item = ProposalItem(
            id = ProposalItemId.fromString(itemId),
            proposalId = ProposalId.fromString(proposalId),
            kind = ProposalItemKind.SetTaskField(TaskValue.Priority(TaskPriority.High)),
            targetId = targetId,
            humanSummary = "Set the priority to High",
            status = ProposalItemStatus.Pending,
            fingerprint = "set-priority-high-$itemId",
        )
        val existing = proposals.watchProposal(ProposalId.fromString(proposalId)).first()
        proposals.save(
            AiProposal(
                id = ProposalId.fromString(proposalId),
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
}

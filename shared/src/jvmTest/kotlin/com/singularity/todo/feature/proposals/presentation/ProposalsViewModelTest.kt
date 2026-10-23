@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.proposals.presentation

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
import com.singularity.todo.feature.proposals.domain.model.TaskValue
import com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.EditProposalItemUseCase
import com.singularity.todo.feature.proposals.domain.usecase.ProposalDispatch
import com.singularity.todo.feature.proposals.domain.usecase.ProposalPlanner
import com.singularity.todo.feature.projects.domain.port.ProjectsRepository
import com.singularity.todo.feature.projects.domain.usecase.DeleteProjectUseCase
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.domain.model.TaskId
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.singularity.todo.feature.tasks.domain.model.TaskPriority

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

    // The scope is the test's background scope, so the collector runs on the test
    // scheduler — `runCurrent` decides when it has read the repository — and the
    // endless collection it starts is cancelled with the test instead of outliving
    // it.
    private fun viewModel(scope: CoroutineScope) = ProposalsViewModel(
        proposals = proposals,
        applyProposalItem = useCase,
        editProposalItem = EditProposalItemUseCase(proposals),
        currentUser = FakeProfileAwareCurrentUser(user),
        scope = AutoCloseableCoroutineScope(scope.coroutineContext),
    )

    @Test
    fun `the waiting changes are shown`() = runTest {
        seedProposal()

        val viewModel = viewModel(backgroundScope)
        runCurrent()

        val state = viewModel.stateFlow.value
        assertEquals(1, state.proposals.size, "the proposal is there")
        assertEquals(1, state.proposals.first().items.size, "with its change")
        assertEquals("Set the priority to High", state.proposals.first().items.first().humanSummary)
    }

    @Test
    fun `the user applies a change`() = runTest {
        val itemId = seedProposal()

        val viewModel = viewModel(backgroundScope)
        runCurrent()

        viewModel.onIntent(ProposalsIntent.Confirm(itemId))
        runCurrent()

        val items = proposals.watchProposal(ProposalId.fromString(proposalId)).first()!!.items
        assertEquals(ProposalItemStatus.Confirmed, items.first().status, "the change is applied")
    }

    @Test
    fun `the user turns a change down`() = runTest {
        val itemId = seedProposal()

        val viewModel = viewModel(backgroundScope)
        runCurrent()

        viewModel.onIntent(ProposalsIntent.Reject(itemId))
        runCurrent()

        val items = proposals.watchProposal(ProposalId.fromString(proposalId)).first()!!.items
        assertEquals(ProposalItemStatus.Rejected, items.first().status, "nothing is applied")
    }

    @Test
    fun `a decided change is no longer waiting`() = runTest {
        val itemId = seedProposal()

        val viewModel = viewModel(backgroundScope)
        runCurrent()

        viewModel.onIntent(ProposalsIntent.Confirm(itemId))
        runCurrent()

        val state = viewModel.stateFlow.value
        assertTrue(state.proposals.isEmpty(), "the decided change is gone")
    }

    @Test
    fun `the user accepts the whole proposal`() = runTest {
        seedProposal()

        val viewModel = viewModel(backgroundScope)
        runCurrent()

        viewModel.onIntent(ProposalsIntent.ConfirmAll(ProposalId.fromString(proposalId)))
        runCurrent()

        val items = proposals.watchProposal(ProposalId.fromString(proposalId)).first()!!.items
        assertEquals(ProposalItemStatus.Confirmed, items.first().status, "every change is applied")
    }

    @Test
    fun `the user dismisses the whole proposal`() = runTest {
        seedProposal()

        val viewModel = viewModel(backgroundScope)
        runCurrent()

        viewModel.onIntent(ProposalsIntent.Dismiss(ProposalId.fromString(proposalId)))
        runCurrent()

        val items = proposals.watchProposal(ProposalId.fromString(proposalId)).first()!!.items
        assertEquals(ProposalItemStatus.Rejected, items.first().status, "every change is turned down")
    }

    @Test
    fun `the user changes the value of a change before applying it`() = runTest {
        val itemId = seedProposal()

        val viewModel = viewModel(backgroundScope)
        runCurrent()

        viewModel.onIntent(ProposalsIntent.Edit(itemId, "medium"))
        runCurrent()

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

        val viewModel = viewModel(backgroundScope)
        runCurrent()

        viewModel.onIntent(ProposalsIntent.Edit(itemId, "whenever"))
        runCurrent()

        val state = viewModel.stateFlow.value
        val reason = state.failures[itemId]
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

        val viewModel = viewModel(backgroundScope)
        runCurrent()

        viewModel.onIntent(ProposalsIntent.Confirm(itemId))
        runCurrent()

        val reason = viewModel.stateFlow.value.failures[itemId]
        assertNotNull(reason, "the reason is shown next to the change that failed")
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
        val id = ProposalId.fromString(proposalId)
        val item = ProposalItem(
            id = ProposalItemId.fromString("item-1"),
            proposalId = id,
            kind = ProposalItemKind.SetTaskField(TaskValue.Priority(TaskPriority.High)),
            targetId = taskId.value,
            humanSummary = "Set the priority to High",
            status = ProposalItemStatus.Pending,
            fingerprint = "set-priority-high",
        )
        proposals.save(
            AiProposal(
                id = id,
                targetKind = AiProposal.TARGET_KIND_TASK,
                targetId = taskId.value,
                userId = user,
                source = ProposalSource.Detail,
                status = ProposalStatus.Pending,
                createdAt = clock.now(),
                updatedAt = clock.now(),
                items = listOf(item),
            ),
        )
        return item.id
    }
}

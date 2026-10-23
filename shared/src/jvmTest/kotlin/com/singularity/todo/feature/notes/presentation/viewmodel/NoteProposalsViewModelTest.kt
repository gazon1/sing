@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.notes.presentation.viewmodel

import com.singularity.todo.core.coroutines.AutoCloseableCoroutineScope
import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.NoteValue
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The note editor's proposal ViewModel.
 *
 * The note's own proposed changes, and the user's word on them.
 */
@Tag("fast")
class NoteProposalsViewModelTest {

    private val clock = FakeClock()

    // The fakes scope every read to their own current user, which defaults to
    // TestUsers.DEFAULT. Fixtures must be stamped with the same id or they seed
    // successfully and are then invisible to the code under test.
    private val user = TestUsers.DEFAULT
    private val noteId = NoteId.fromString("n1")
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

    // The scope is the test's background scope, so the collector runs on the test
    // scheduler — `runCurrent` decides when it has read the repository — and the
    // endless collection it starts is cancelled with the test instead of outliving
    // it.
    private fun viewModel(scope: CoroutineScope) = NoteProposalsViewModel(
        noteId = noteId,
        proposals = proposals,
        applyProposalItem = useCase,
        editProposalItem = EditProposalItemUseCase(proposals),
        currentUser = FakeProfileAwareCurrentUser(user),
        scope = AutoCloseableCoroutineScope(scope.coroutineContext),
    )

    @Test
    fun `the proposed changes are shown`() = runTest {
        seedProposal()

        val viewModel = viewModel(backgroundScope)
        runCurrent()

        val state = viewModel.stateFlow.value
        assertEquals(1, state.proposals.size, "the proposal is there")
        assertEquals(1, state.proposals.first().pendingItems.size, "with its change")
    }

    @Test
    fun `the user applies the change`() = runTest {
        val itemId = seedProposal()

        val viewModel = viewModel(backgroundScope)
        runCurrent()

        viewModel.onIntent(NoteProposalsIntent.Confirm(itemId))
        runCurrent()

        val items = proposals.watchProposal(proposalId).first()!!.items
        assertEquals(ProposalItemStatus.Confirmed, items.first().status, "the change is applied")
    }

    @Test
    fun `the user turns the change down`() = runTest {
        val itemId = seedProposal()

        val viewModel = viewModel(backgroundScope)
        runCurrent()

        viewModel.onIntent(NoteProposalsIntent.Reject(itemId, null))
        runCurrent()

        val items = proposals.watchProposal(proposalId).first()!!.items
        assertEquals(ProposalItemStatus.Rejected, items.first().status, "nothing is applied")
    }

    @Test
    fun `a decided change is no longer waiting`() = runTest {
        val itemId = seedProposal()

        val viewModel = viewModel(backgroundScope)
        runCurrent()

        viewModel.onIntent(NoteProposalsIntent.Confirm(itemId))
        runCurrent()

        assertTrue(viewModel.stateFlow.value.proposals.isEmpty(), "the decided change is gone")
    }

    @Test
    fun `a refused change keeps its reason next to it`() = runTest {
        // The value the user typed does not read, so the change is refused — and the
        // reason has to be visible. A refusal the user cannot see is a refusal the
        // user can only guess at.
        val itemId = seedProposal(
            kind = ProposalItemKind.SetTaskField(TaskValue.Priority(TaskPriority.High)),
        )

        val viewModel = viewModel(backgroundScope)
        runCurrent()

        viewModel.onIntent(NoteProposalsIntent.Edit(itemId, "whenever"))
        runCurrent()

        val failure = viewModel.stateFlow.value.failures[itemId]
        assertTrue(
            failure != null && failure.contains("whenever"),
            "the reason sits next to the change: ${viewModel.stateFlow.value.failures}",
        )
    }

    @Test
    fun `a change that goes through leaves no reason behind`() = runTest {
        val itemId = seedProposal(
            kind = ProposalItemKind.SetTaskField(TaskValue.Priority(TaskPriority.High)),
        )

        val viewModel = viewModel(backgroundScope)
        runCurrent()

        // A refused change leaves its reason behind; a change that goes through takes it away.
        viewModel.onIntent(NoteProposalsIntent.Edit(itemId, "whenever"))
        runCurrent()
        assertTrue(viewModel.stateFlow.value.failures.isNotEmpty(), "the refusal left its reason")

        viewModel.onIntent(NoteProposalsIntent.Edit(itemId, "high"))
        runCurrent()

        assertTrue(
            viewModel.stateFlow.value.failures.isEmpty(),
            "there is nothing to explain: ${viewModel.stateFlow.value.failures}",
        )
    }

    /** Seeds the note the proposal is about, and a one-item proposal for it. */
    private suspend fun seedProposal(
        kind: ProposalItemKind = ProposalItemKind.SetNoteField(NoteValue.Body("a shorter body")),
    ): ProposalItemId {
        notes.seed(
            Note(
                id = noteId,
                userId = user,
                title = "Original",
                createdAt = clock.now(),
                updatedAt = clock.now(),
            ),
        )
        val item = ProposalItem(
            id = ProposalItemId.fromString("item-1"),
            proposalId = proposalId,
            kind = kind,
            targetId = noteId.value,
            humanSummary = "Rewrite the body",
            status = ProposalItemStatus.Pending,
            fingerprint = "note-body-1",
        )
        proposals.save(
            AiProposal(
                id = proposalId,
                targetKind = AiProposal.TARGET_KIND_NOTE,
                targetId = noteId.value,
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

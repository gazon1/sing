@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.singularity.todo.feature.notes.presentation.viewmodel

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
import com.singularity.todo.test.helpers.TestVmContext
import com.singularity.todo.test.helpers.VmScope
import com.singularity.todo.test.helpers.testVm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    /**
     * The ViewModel under test.
     *
     * Its scope is a child of the background scope: the collector it starts never
     * ends, and a collector on the test's own scope would keep the test from
     * finishing. The work the intents start still runs on the test scheduler.
     */
    private fun TestScope.viewModel(): TestVmContext<NoteProposalsViewModel, NoteProposalsUiState> =
        testVm(stateAccessor = { vm: NoteProposalsViewModel -> vm.state }, vmScope = VmScope.Background) { scope ->
            NoteProposalsViewModel(
                noteId = noteId,
                proposals = proposals,
                applyProposalItem = useCase,
                editProposalItem = EditProposalItemUseCase(proposals),
                currentUser = FakeProfileAwareCurrentUser(user),
                scope = scope,
            )
        }

    @Test
    fun `the proposed changes are shown`() = runTest {
        seedProposal()

        val ctx = viewModel()

        val state = ctx.state.value
        assertEquals(1, state.proposals.size, "the proposal is there")
        assertEquals(1, state.proposals.first().pendingItems.size, "with its change")
    }

    @Test
    fun `the user applies the change`() = runTest {
        val itemId = seedProposal()

        val ctx = viewModel()

        ctx.act { onIntent(NoteProposalsIntent.Confirm(itemId)) }

        val items = proposals.watchProposal(proposalId).first()!!.items
        assertEquals(ProposalItemStatus.Confirmed, items.first().status, "the change is applied")
    }

    @Test
    fun `the user turns the change down`() = runTest {
        val itemId = seedProposal()

        val ctx = viewModel()

        ctx.act { onIntent(NoteProposalsIntent.Reject(itemId, null)) }

        val items = proposals.watchProposal(proposalId).first()!!.items
        assertEquals(ProposalItemStatus.Rejected, items.first().status, "nothing is applied")
    }

    @Test
    fun `the reason the user typed is not kept here`() = runTest {
        val itemId = seedProposal()

        val ctx = viewModel()

        ctx.act { onIntent(NoteProposalsIntent.Reject(itemId, "the body is right already")) }

        val items = proposals.watchProposal(proposalId).first()!!.items
        assertNull(
            items.first().rejectionReason,
            "this editor has always dropped the reason the user typed",
        )
    }

    @Test
    fun `a decided change is no longer waiting`() = runTest {
        val itemId = seedProposal()

        val ctx = viewModel()

        ctx.act { onIntent(NoteProposalsIntent.Confirm(itemId)) }

        assertTrue(ctx.state.value.proposals.isEmpty(), "the decided change is gone")
    }

    @Test
    fun `a refused change keeps its reason next to it`() = runTest {
        // The value the user typed does not read, so the change is refused — and the
        // reason has to be visible. A refusal the user cannot see is a refusal the
        // user can only guess at.
        val itemId = seedProposal(
            kind = ProposalItemKind.SetTaskField(TaskValue.Priority(TaskPriority.High)),
        )

        val ctx = viewModel()

        ctx.act { onIntent(NoteProposalsIntent.Edit(itemId, "whenever")) }

        val failure = ctx.state.value.failures[itemId]
        assertTrue(
            failure != null && failure.contains("whenever"),
            "the reason sits next to the change: ${ctx.state.value.failures}",
        )
    }

    @Test
    fun `a change that goes through leaves no reason behind`() = runTest {
        val itemId = seedProposal(
            kind = ProposalItemKind.SetTaskField(TaskValue.Priority(TaskPriority.High)),
        )

        val ctx = viewModel()

        // A refused change leaves its reason behind; a change that goes through takes it away.
        ctx.act { onIntent(NoteProposalsIntent.Edit(itemId, "whenever")) }
        assertTrue(ctx.state.value.failures.isNotEmpty(), "the refusal left its reason")

        ctx.act { onIntent(NoteProposalsIntent.Edit(itemId, "high")) }

        assertTrue(
            ctx.state.value.failures.isEmpty(),
            "there is nothing to explain: ${ctx.state.value.failures}",
        )
    }

    @Test
    fun `the user turns the whole proposal down`() = runTest {
        seedProposal()

        val ctx = viewModel()

        ctx.act { onIntent(NoteProposalsIntent.Dismiss(proposalId)) }

        val items = proposals.watchProposal(proposalId).first()!!.items
        assertEquals(ProposalItemStatus.Rejected, items.first().status, "every change is turned down")
    }

    @Test
    fun `a turned down change leaves no reason behind`() = runTest {
        // The note is not seeded, so the change cannot be applied — and the reason
        // sits next to it. Turning the proposal down answers the change, so the
        // reason has nothing left to explain.
        val itemId = seedProposal(seedNote = false)

        val ctx = viewModel()

        ctx.act { onIntent(NoteProposalsIntent.Confirm(itemId)) }
        assertNotNull(ctx.state.value.failures[itemId], "the refusal left its reason")

        ctx.act { onIntent(NoteProposalsIntent.Dismiss(proposalId)) }

        assertTrue(
            ctx.state.value.failures.isEmpty(),
            "there is nothing left to explain: ${ctx.state.value.failures}",
        )
    }

    /** Seeds the note the proposal is about, and a one-item proposal for it. */
    private suspend fun seedProposal(
        kind: ProposalItemKind = ProposalItemKind.SetNoteField(NoteValue.Body("a shorter body")),
        seedNote: Boolean = true,
    ): ProposalItemId {
        if (seedNote) {
            notes.seed(
                Note(
                    id = noteId,
                    userId = user,
                    title = "Original",
                    createdAt = clock.now(),
                    updatedAt = clock.now(),
                ),
            )
        }
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

package com.singularity.todo.feature.proposals

import com.singularity.todo.core.ids.ProposalId
import com.singularity.todo.core.ids.ProposalItemId
import com.singularity.todo.core.ids.UserId
import com.singularity.todo.feature.notes.NoteId
import com.singularity.todo.feature.proposals.domain.model.AiProposal
import com.singularity.todo.feature.proposals.domain.model.ProposalItem
import com.singularity.todo.feature.proposals.domain.model.ProposalItemKind
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalSource
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
import com.singularity.todo.feature.proposals.domain.model.NoteField
import com.singularity.todo.test.fakes.FakeClock
import com.singularity.todo.test.fakes.FakeProposalRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The proposals of a note are reachable.
 *
 * A proposal the user cannot see is a proposal the user can only answer by telling
 * the assistant to stop. These are the note's twins of the task's proposals.
 */
@Tag("fast")
class NoteProposalsPortTest {

    private val now = Instant.parse("2026-03-10T09:00:00Z")

    @Test
    fun `the proposals of a note are watched`() = runTest {
        val repository = FakeProposalRepository(FakeClock(now))
        val noteId = NoteId.fromString("note-1")
        repository.save(proposalFor(noteId.value)).getOrThrow()

        val watched = repository.watchProposalsForNote(noteId).first()

        assertEquals(1, watched.size, "the note's proposal is there")
        assertEquals(noteId.value, watched.first().targetId)
    }

    @Test
    fun `another note's proposals are not watched`() = runTest {
        val repository = FakeProposalRepository(FakeClock(now))
        repository.save(proposalFor("note-1")).getOrThrow()

        val watched = repository.watchProposalsForNote(NoteId.fromString("note-2")).first()

        assertEquals(0, watched.size, "a note sees only its own")
    }

    private fun proposalFor(noteId: String): AiProposal {
        val proposalId = ProposalId.fromString("prop-1")
        return AiProposal(
            id = proposalId,
            targetKind = AiProposal.TARGET_KIND_NOTE,
            targetId = noteId,
            userId = UserId.anonymous,
            source = ProposalSource.Detail,
            status = ProposalStatus.Pending,
            createdAt = now,
            updatedAt = now,
            items = listOf(
                ProposalItem(
                    id = ProposalItemId.fromString("item-1"),
                    proposalId = proposalId,
                    kind = ProposalItemKind.SetNoteField(NoteField.Body, "a shorter body"),
                    targetId = noteId,
                    humanSummary = "Rewrite the body",
                    status = ProposalItemStatus.Pending,
                    fingerprint = "note-body-1",
                ),
            ),
        )
    }
}

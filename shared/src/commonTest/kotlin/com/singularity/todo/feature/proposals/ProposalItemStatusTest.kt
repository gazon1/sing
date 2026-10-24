package com.singularity.todo.feature.proposals

import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.isDecided
import com.singularity.todo.feature.proposals.domain.model.isPending
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A status this build does not know is not a status it may read as another.
 *
 * ## Why this is a test
 *
 * The reader used to fill the gap: a status it could not name became
 * `Pending`. And `Pending` is the one status that says "this change can still be
 * applied" — so a row written by a build that knew a status this one does not
 * read as a change that is still waiting, and the apply use case, which asks
 * exactly one question, applies it a second time.
 *
 * The reader does not fill the gap any more: an unknown status is
 * [ProposalItemStatus.Unknown], and it is neither waiting nor decided.
 */
@Tag("fast")
class ProposalItemStatusTest {

    @Test
    fun `a status this build knows reads as itself`() {
        assertEquals(ProposalItemStatus.Pending, ProposalItemStatus.read("Pending"))
        assertEquals(ProposalItemStatus.Confirmed, ProposalItemStatus.read("Confirmed"))
        assertEquals(ProposalItemStatus.Rejected, ProposalItemStatus.read("Rejected"))
        assertEquals(ProposalItemStatus.Retracted, ProposalItemStatus.read("Retracted"))
    }

    @Test
    fun `a status this build does not know is not read as waiting`() {
        val read = ProposalItemStatus.read("Applied")

        assertIs<ProposalItemStatus.Unknown>(read, "an unknown status is not read as another one")
        assertEquals("Applied", read.raw, "the status is kept exactly as it was found")
        assertFalse(read.isPending, "a change this build cannot place is not waiting to be applied")
    }

    @Test
    fun `a status this build does not know is not a decision`() {
        // The user never said yes and never said no, so the change is still theirs
        // to turn down — and the card must keep it.
        val read = ProposalItemStatus.read("Applied")

        assertFalse(read.isDecided, "a change nobody decided about is not decided")
    }

    @Test
    fun `a status this build knows is a decision when it says so`() {
        assertTrue(ProposalItemStatus.Confirmed.isDecided)
        assertTrue(ProposalItemStatus.Rejected.isDecided)
        assertTrue(ProposalItemStatus.Retracted.isDecided)
        assertFalse(ProposalItemStatus.Pending.isDecided, "a change that waits is not decided")
    }

    @Test
    fun `a status this build does not know keeps its name`() {
        // The row can be written back unchanged: a row this build cannot read must
        // not become a row this build rewrites into something else.
        val read = ProposalItemStatus.read("Applied")

        assertEquals("Applied", read.name, "the name the row carries is the name that was found")
    }
}

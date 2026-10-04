package com.singularity.todo.feature.proposals

import com.singularity.todo.feature.proposals.domain.logic.ProposalStatusReducer
import com.singularity.todo.feature.proposals.domain.model.ProposalItemStatus
import com.singularity.todo.feature.proposals.domain.model.ProposalStatus
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Total function over every status combination, so the card's aggregate state is
 * pinned by construction rather than by whatever combination happened to be exercised
 * in a UI test.
 */
@Tag("fast")
class ProposalStatusReducerTest {

    @Test
    fun `an empty proposal is resolved`() {
        // Nothing outstanding for the card to prompt about, so "Resolved" is honest.
        assertEquals(ProposalStatus.Resolved, ProposalStatusReducer.reduce(emptyList()))
    }

    @Test
    fun `a single pending item keeps the proposal pending`() {
        assertEquals(
            ProposalStatus.Pending,
            ProposalStatusReducer.reduce(listOf(ProposalItemStatus.Pending)),
        )
    }

    @Test
    fun `pending wins over every other status`() {
        val all = ProposalItemStatus.entries
        for (other in all - ProposalItemStatus.Pending) {
            assertEquals(
                ProposalStatus.Pending,
                ProposalStatusReducer.reduce(listOf(other, ProposalItemStatus.Pending)),
                "a pending item must keep the card open regardless of $other",
            )
        }
    }

    @Test
    fun `all confirmed is resolved`() {
        assertEquals(
            ProposalStatus.Resolved,
            ProposalStatusReducer.reduce(List(3) { ProposalItemStatus.Confirmed }),
        )
    }

    @Test
    fun `confirmed mixed with rejected is partially resolved`() {
        assertEquals(
            ProposalStatus.PartiallyResolved,
            ProposalStatusReducer.reduce(
                listOf(ProposalItemStatus.Confirmed, ProposalItemStatus.Rejected),
            ),
        )
    }

    @Test
    fun `any retraction retracts the whole proposal`() {
        // The model took it back — surfacing the remaining items would be wrong even
        // though none of them is pending.
        assertEquals(
            ProposalStatus.Retracted,
            ProposalStatusReducer.reduce(
                listOf(ProposalItemStatus.Confirmed, ProposalItemStatus.Retracted),
            ),
        )
    }

    @Test
    fun `pending outranks retraction`() {
        // Mixed state is possible if a retraction lands while a new item was added.
        // Pending is the safer read: the card must not disappear from under a
        // decision the user has not made.
        assertEquals(
            ProposalStatus.Pending,
            ProposalStatusReducer.reduce(
                listOf(ProposalItemStatus.Retracted, ProposalItemStatus.Pending),
            ),
        )
    }

    @Test
    fun `all rejected is partially resolved rather than resolved`() {
        // The user saw the card and declined all of it. Reporting "Resolved" would
        // read as "applied", which is the opposite of what happened.
        assertEquals(
            ProposalStatus.PartiallyResolved,
            ProposalStatusReducer.reduce(
                listOf(ProposalItemStatus.Rejected, ProposalItemStatus.Rejected),
            ),
        )
    }
}

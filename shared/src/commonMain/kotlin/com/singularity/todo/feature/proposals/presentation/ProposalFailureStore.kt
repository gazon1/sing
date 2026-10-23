package com.singularity.todo.feature.proposals.presentation

import com.singularity.todo.core.ids.ProposalItemId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the reason a change failed lives.
 *
 * The reason belongs next to the change it belongs to — under the change on the
 * card, in the same state the card reads. Where "next to" is depends on the
 * surface: the review screen and the note editor keep the reasons in their own
 * state, the task detail in a flow of its own that the assembled state reads.
 *
 * The shared decisions write through this and never look at it.
 */
interface ProposalFailureStore {

    /** Keeps [message] next to the change it belongs to. */
    fun record(itemId: ProposalItemId, message: String)

    /** Forgets the reason of a change that worked. */
    fun clear(itemId: ProposalItemId)
}

/**
 * A store that keeps the reasons in a flow of its own.
 *
 * The task detail's reasons live outside the assembled state on purpose: that
 * state is rebuilt from nine combined inputs, and a reason kept inside it would
 * be lost on every rebuild.
 */
class MutableProposalFailureStore : ProposalFailureStore {

    private val reasons = MutableStateFlow<Map<ProposalItemId, String>>(emptyMap())

    /** The reasons, as the surface's state shows them. */
    val state: StateFlow<Map<ProposalItemId, String>> = reasons.asStateFlow()

    override fun record(itemId: ProposalItemId, message: String) {
        reasons.value = reasons.value + (itemId to message)
    }

    override fun clear(itemId: ProposalItemId) {
        reasons.value = reasons.value - itemId
    }
}

/**
 * A store that keeps the reasons in a state holder of the surface's own shape.
 *
 * A surface whose state already carries the reasons writes them through this
 * instead of keeping a second copy of them.
 */
fun proposalFailureStore(
    onRecord: (ProposalItemId, String) -> Unit,
    onClear: (ProposalItemId) -> Unit,
): ProposalFailureStore = object : ProposalFailureStore {
    override fun record(itemId: ProposalItemId, message: String) {
        onRecord(itemId, message)
    }

    override fun clear(itemId: ProposalItemId) {
        onClear(itemId)
    }
}

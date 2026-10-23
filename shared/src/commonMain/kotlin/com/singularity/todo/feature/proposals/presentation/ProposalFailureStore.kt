package com.singularity.todo.feature.proposals.presentation

import com.singularity.todo.core.ids.ProposalItemId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the reason a change failed lives.
 *
 * One store, one implementation. The reason belongs next to the change it belongs
 * to — under the change on the card, in the same state the card reads. Three
 * surfaces show changes — the review screen, the note editor and the task detail
 * — and they used to keep the reasons three different ways: two of them wrote
 * into the surface's own state, the third kept a flow of its own, because that
 * state's assembled from nine combined inputs and a reason kept inside it was
 * lost on every rebuild.
 *
 * The two ways are now one: the store keeps the reasons in a flow, and a surface
 * that draws them in its own state mirrors that flow. The store owns the
 * reasons; a surface shows them.
 */
class ProposalFailureStore {

    private val reasons = MutableStateFlow<Map<ProposalItemId, String>>(emptyMap())

    /** The reasons, as the surface's state shows them. */
    val state: StateFlow<Map<ProposalItemId, String>> = reasons.asStateFlow()

    /** Keeps [message] next to the change it belongs to. */
    fun record(itemId: ProposalItemId, message: String) {
        reasons.value = reasons.value + (itemId to message)
    }

    /** Forgets the reason of a change that worked. */
    fun clear(itemId: ProposalItemId) {
        reasons.value = reasons.value - itemId
    }
}

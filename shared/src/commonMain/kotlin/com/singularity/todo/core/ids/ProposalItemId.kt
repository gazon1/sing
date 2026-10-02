package com.singularity.todo.core.ids

import kotlinx.serialization.Serializable

/**
 * Identity for an [com.singularity.todo.feature.proposals.domain.ProposalItem].
 *
 * Items are one-shot: a single tap on confirm/reject transitions the item out of
 * [com.singularity.todo.feature.proposals.domain.ProposalItemStatus.Pending] exactly
 * once, so a double-tap cannot apply twice.
 */
@Serializable
@JvmInline
value class ProposalItemId(val value: String) {
    companion object {
        fun generate() = ProposalItemId(nextId())
        fun fromString(value: String) = ProposalItemId(value)
    }
}

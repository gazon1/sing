package com.singularity.todo.core.ids

import kotlinx.serialization.Serializable

/**
 * Identity for an [com.singularity.todo.feature.proposals.domain.AiProposal].
 */
@Serializable
@JvmInline
value class ProposalId(val value: String) {
    companion object {
        fun generate() = ProposalId(nextId())
        fun fromString(value: String) = ProposalId(value)
    }
}

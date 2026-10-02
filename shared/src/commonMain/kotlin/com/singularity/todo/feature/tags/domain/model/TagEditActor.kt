package com.singularity.todo.feature.tags.domain.model

/**
 * Identifies the actor performing a tag edit, for suppression tracking.
 *
 * When [User] removes a tag → the removal is recorded as suppression.
 * When [User] adds a tag manually → suppression for that tag is cleared (user overrides AI).
 * When [AiProposal] suppresses → existing suppression is preserved.
 */
enum class TagEditActor {
    /** A human user editing tags in the UI. */
    User,

    /** An AI proposal applying a tag change via [com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase]. */
    AiProposal,
}

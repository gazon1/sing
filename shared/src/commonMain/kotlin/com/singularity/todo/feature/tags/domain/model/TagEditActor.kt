package com.singularity.todo.feature.tags.domain.model

/**
 * Identifies the actor performing a tag edit, for suppression tracking.
 *
 * When [User] removes a tag → the removal is recorded as suppression.
 * When [User] adds a tag manually → suppression for that tag is cleared (user overrides AI).
 * When [UserUndo] puts a tag back → the suppression stands (the AI stays quiet about it).
 * When [AiProposal] suppresses → existing suppression is preserved.
 */
enum class TagEditActor {
    /** A human user editing tags in the UI. */
    User,

    /**
     * The user put a tag back after taking it off.
     *
     * The tag comes back, and the AI stays quiet about it: the user removed the tag
     * on purpose, and putting it back is the undo of that removal, not a new decision
     * to have it.
     */
    UserUndo,

    /** An AI proposal applying a tag change via [com.singularity.todo.feature.proposals.domain.usecase.ApplyProposalItemUseCase]. */
    AiProposal,
}

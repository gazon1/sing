package com.singularity.todo.feature.proposals.presentation

/**
 * The three ways a surface words a proposal decision.
 *
 * ## Why the styles live together
 *
 * A proposal is answered in three places — the review screen, the note editor and
 * the task detail — and each of them used to carry its own style where it
 * happened to be written: the review screen's in its ViewModel, the note
 * editor's in its ViewModel, the task detail's in its coordinator. A style is a
 * value, and the values belong together: a change of the wording is a change of
 * what the user reads in three places, and it should be one edit — not three
 * edits that happen to be in three files.
 *
 * The values themselves are unchanged; only their address moved.
 */
object ProposalDecisionStyles {

    /**
     * The review screen — the screen where the waiting changes are listed.
     *
     * A dismissed proposal keeps no reason per change: the proposal's own status
     * says what became of it.
     */
    val reviewScreen = ProposalDecisionStyle(
        apply = "Apply the change",
        reject = "Reject the change",
        applyAll = "Apply the changes",
        applyOne = "Apply the change",
        dismiss = "Dismiss the proposal",
        dismissOne = null,
        change = "Change the proposal",
    )

    /**
     * The note editor — the changes waiting for a decision on one note.
     *
     * The reason the user typed is not stored with the refusal here: this editor
     * has always dropped it, and the shared operations keep that as it was
     * rather than changing what the model is told.
     */
    val noteEditor = ProposalDecisionStyle(
        apply = "Apply the change",
        reject = "Reject the change",
        applyAll = "Apply the changes",
        applyOne = "Apply the change",
        dismiss = "Turn the changes down",
        dismissOne = "Turn the change down",
        change = "Change the proposal",
        storesRejectionReason = false,
    )

    /**
     * The task detail — the changes waiting for a decision on one task.
     *
     * The task detail names the operation instead of describing it, and it says
     * how many changes of a batch could not be applied — a batch where some
     * changes failed is a normal outcome, but the user is told which changes it
     * was.
     */
    val taskDetail = ProposalDecisionStyle(
        apply = "Confirm",
        reject = "Reject",
        applyAll = "Apply",
        applyOne = "Apply",
        dismiss = "Dismiss",
        dismissOne = null,
        change = "Change",
        failureText = ProposalFailureText { label, error -> "$label failed: ${error.message}" },
        batchFailureText = { count -> "$count items could not be applied" },
    )
}

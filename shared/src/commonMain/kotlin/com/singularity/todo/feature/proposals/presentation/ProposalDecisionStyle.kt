package com.singularity.todo.feature.proposals.presentation

import com.singularity.todo.core.error.toMessage

/**
 * How one surface words a proposal decision, and what it does with a failure.
 *
 * ## Why the difference is a value and not a branch
 *
 * Three surfaces show a proposal: the review screen, the note editor and the task
 * detail. They do the same five things, and they do them in the same order — but
 * they say it differently, and two of them keep slightly different things. A
 * shared implementation that asked "which surface is this?" would be a shared
 * implementation with a switch in it, and the switch would grow.
 *
 * So the difference is a value: the surface builds its own style and hands it
 * over. The shared operations read the labels and the flags and never ask who
 * they serve.
 *
 * @property apply The label of "apply one change".
 * @property reject The label of "turn one change down".
 * @property applyAll The label of "apply every change of a proposal".
 * @property applyOne The label of the reason one change of a batch carries.
 * @property dismiss The label of "turn every change of a proposal down".
 * @property dismissOne The label of the reason one change of a dismissed
 *   proposal carries. Null when the surface keeps no reason for a change it
 *   could not turn down.
 * @property change The label of "change the value of a change".
 * @property failureText How the reason a change failed reads in this surface.
 * @property storesRejectionReason Whether the reason the user typed is stored
 *   with the refusal.
 * @property showsThrownFailures Whether a failure that arrives as a thrown
 *   exception is shown to the user. A surface that says no leaves it to its
 *   scope's failure handler, which reports it and shows nothing.
 * @property batchFailureText The message a partly failed batch shows. Null
 *   shows nothing: a batch where some changes failed is a normal outcome, not
 *   an error.
 */
data class ProposalDecisionStyle(
    val apply: String,
    val reject: String,
    val applyAll: String,
    val applyOne: String,
    val dismiss: String,
    val dismissOne: String?,
    val change: String,
    val failureText: ProposalFailureText = ProposalFailureText { label, error -> error.toMessage(label) },
    val storesRejectionReason: Boolean = true,
    val showsThrownFailures: Boolean = true,
    val batchFailureText: ((Int) -> String)? = null,
)

/**
 * How a surface words the reason a change failed.
 *
 * The MVI surfaces show the failure's own message; the task detail prefixes the
 * name of the operation. The reason sits next to the change it belongs to, so
 * this is a sentence the user reads in place of the change — not a log line.
 */
fun interface ProposalFailureText {
    /**
     * The reason, as the user reads it.
     *
     * @param label what was being done, in the surface's own words.
     * @param error the failure itself.
     */
    fun text(label: String, error: Throwable): String
}

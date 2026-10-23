package com.singularity.todo.feature.proposals.presentation

/**
 * Where a failure goes.
 *
 * The MVI surfaces report a failure to the crash reporter and then show it as a
 * one-shot event; the task detail shows it as an event and reports nothing
 * further. The shared decisions do not know which is which — they hand the
 * failure over and let the surface decide.
 */
fun interface ProposalErrorSink {

    /**
     * Reports a failure of [label].
     *
     * @param label what was being done, in the surface's own words.
     * @param message the reason, as the user reads it.
     * @param error the failure itself. A surface that reports failures further
     *   needs the failure, not only the sentence about it.
     */
    suspend fun report(label: String, message: String, error: Throwable)
}

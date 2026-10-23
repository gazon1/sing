package com.singularity.todo.feature.proposals.presentation

/**
 * Where a failure goes.
 *
 * Every surface that shows a change shows the failure of that change too — a
 * failure the user cannot see is a change nobody decided about. What differs
 * between the surfaces is *where* the failure is shown: the MVI surfaces show it
 * as a one-shot event, the task detail as its own error event.
 *
 * The sink is where the failure is reported as well: a surface that catches a
 * failure has to report it, because the scope's handler no longer sees it.
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

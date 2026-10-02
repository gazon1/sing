package com.singularity.todo.feature.tasks.presentation.state

/**
 * Decides whether a task should still be offered first-run scaffolding.
 *
 * Pure and clock-injected so the rules are testable without fakes: the interesting cases
 * are all about *which cheap answer wins*, not about any I/O.
 */
object FirstRunResolver {

    /** A task older than this is not "new" any more, whatever it contains. */
    const val NEW_TASK_WINDOW_MS: Long = 5 * 60 * 1000L

    /**
     * Resolves first-run state from the cheap answers already in hand.
     *
     * The order below is the whole point of this function — every clause exists because
     * a cheaper answer arrived first:
     *
     * 1. Body text or any checklist item settles it: the user is already working, and
     *    suggestions on top of content are noise.
     * 2. A subtask that is *done* settles it. A subtask that is merely *open* does not —
     *    adding it already was the structuring step, so the banner would be redundant.
     * 3. An unreadable or failed load settles it as established. Suggesting actions on a
     *    task we failed to read is worse than suggesting nothing.
     * 4. Only then does the age window decide: new and empty gets [FirstRun.Offer].
     *
     * @param hasBody whether the description field carries non-whitespace text.
     * @param checklistCount how many checklist items the task has.
     * @param completedSubtaskCount how many direct children are already done.
     * @param ageMs how long since the task was created, from an injected clock.
     * @param loadFailed whether the supporting reads failed.
     */
    fun resolve(
        hasBody: Boolean,
        checklistCount: Int,
        completedSubtaskCount: Int,
        ageMs: Long,
        loadFailed: Boolean = false,
    ): FirstRun = when {
        loadFailed -> FirstRun.Established
        hasBody || checklistCount > 0 -> FirstRun.Established
        completedSubtaskCount > 0 -> FirstRun.Established
        ageMs < NEW_TASK_WINDOW_MS -> FirstRun.Offer
        else -> FirstRun.Established
    }
}

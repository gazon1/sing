package com.singularity.todo.core.refresh

import com.singularity.todo.core.sync.SyncOutcome

/**
 * What one refresh produced.
 *
 * The two steps are independent: a refresh that could not reach the server
 * still archives, and a refresh whose archive step failed still synchronises.
 * One failing step never cancels the other — the user asked for both, and
 * doing one of them is better than doing neither.
 *
 * The synchronisation's verdict travels in [sync] unchanged. It already
 * distinguishes "the cycle ran", "the cycle could not run" and "there was no
 * synchronisation to run", and a second vocabulary for the same states would
 * be a second way to be wrong.
 */
data class RefreshOutcome(
    /** The archive step: how many tasks moved, or why it could not run. */
    val archived: Result<Int>,
    /** The synchronisation step's own verdict. */
    val sync: SyncOutcome,
)

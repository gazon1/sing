package com.singularity.todo.core.wipe

/**
 * Port for emptying the active profile's content.
 *
 * ## Why this is a port
 *
 * The reset is reached from a debug deep link, but the behaviour it has is
 * production behaviour: the E2E suite depends on it, and so does any future
 * "start this profile from scratch" action. A debug entry point is not a place
 * to keep a list of twenty-one tables, so the list lives here, in the library,
 * and the entry point only says *reset*.
 *
 * ## The contract
 *
 * - **It deletes the profile's rows, not the live rows.** Archived, trashed and
 *   soft-deleted rows go too. A sweep is defined by the query that feeds it, and
 *   the query that lists "the tasks" is not the query that lists "the tasks that
 *   exist" — a bulk delete has no such filter.
 * - **It is scoped to the active profile.** A table that no profile owns is not
 *   deleted by this reset; see the inventory for the ones and the reasons.
 * - **It leaves the profile itself alone**, together with the sync engine's own
 *   bookkeeping, the remote config cache and the LLM usage log. Those are not
 *   content, and the reasons are in `docs/architecture/wipe-inventory.md`.
 * - **It fails loudly.** A reset that stops half way leaves the next run looking
 *   at rows it should not see, so a partial reset is worse than a failure.
 */
interface ContentResetter {

    /**
     * Empties the active profile's content.
     *
     * @return the tables the reset reached.
     * @throws Exception when a table could not be emptied. The reset does not
     *   continue past the first failure — a half-done reset is not a clean state.
     */
    suspend fun resetContent(): ContentResetResult
}

/**
 * The outcome of a profile reset.
 *
 * @param wipedTables the tables the reset reached, in the order it reached them.
 *   The list comes from [ProfileWipePlan], and `scripts/ci/check-profile-reset-inventory.py`
 *   is what keeps that list and the calls the reset actually makes in step.
 */
data class ContentResetResult(val wipedTables: List<String>)

package com.singularity.todo.core.seed

import kotlinx.coroutines.CompletableDeferred

/**
 * A link that arrived at the debug activity, and the gates that order its seed.
 *
 * Two things travel together, so a caller cannot order the seeds by one and
 * decide who owns the screen by the other:
 *
 * - the **arrival number** — how many links arrived, this one included. The
 *   newest arrival owns the screen.
 * - the **gates** — what this seed waits for, and what waits for it. The seed of
 *   this arrival runs after the seed of the arrival before it, whatever order
 *   the workers reach their locks in.
 */
class SeedArrival internal constructor(
    /** How many links have arrived, this one included. */
    val number: Int,
    /** Completed when the seed of the arrival before this one has finished. */
    internal val previous: CompletableDeferred<Unit>,
    /** Completed when the seed of this arrival has finished. */
    internal val own: CompletableDeferred<Unit>,
)

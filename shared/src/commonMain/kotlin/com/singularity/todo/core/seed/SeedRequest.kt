package com.singularity.todo.core.seed

/**
 * A seed request waiting in the queue.
 *
 * The arrival number travels with the parameters, because the two travel
 * together: the queue needs the parameters to write, and the screen owner is
 * decided by the arrival.
 */
data class SeedRequest(
    /** The arrival that asked for this seed. */
    val arrival: Int,
    /** The seed parameters, as the link carried them. */
    val params: Map<String, String>,
)

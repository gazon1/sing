package com.singularity.todo.core.seed

import kotlin.concurrent.Volatile

/**
 * Counts the seed links that reach the debug activity and says which of them
 * owns the screen.
 *
 * Two rules, one class:
 *
 * **A link is handled once.** The same link comes back through the activity's
 * resume right after its arrival was recorded, and that is not a second seed.
 *
 * **The newest link owns the screen.** A run that a newer link has superseded
 * says nothing: it does not bring the app up and it does not show its
 * failure. Ownership is decided by *arrival*, not by completion — a link that
 * arrived while the seed was running owns the screen even though it has not
 * started yet: it is the one that will write last.
 *
 * The order in which the seeds run is not this class's business: the queue
 * ([SeedQueue]) is the one mechanism that runs them one at a time, in arrival
 * order.
 */
class SeedSequencer {
    /**
     * How many links have arrived. The newest one owns the screen.
     *
     * Written by the thread the links arrive on, read by the thread the seeds
     * run on — volatile, so a seed sees the arrival that superseded it.
     */
    @Volatile
    private var arrivals = 0

    /** The link that arrived last, compared by identity.
     *
     * Read and written by the thread the links arrive on — the main thread —
     * so it needs no lock of its own. The seeds run on another thread and
     * never look at it: they ask [ownsScreen], which reads the volatile
     * counter instead.
     */
    private var lastLink: Any? = null

    /**
     * Registers a link that arrived, and returns its arrival number.
     *
     * Returns null when the link was handled already. The link is an opaque
     * token compared by identity: two deep links with the same text are two
     * links, and the same link twice is one link.
     *
     * Called from the thread the links arrive on — the main thread — so the
     * counter needs no lock.
     */
    fun arrive(link: Any): Int? {
        if (link === lastLink) return null
        lastLink = link
        arrivals += 1
        return arrivals
    }

    /**
     * Whether the arrival still owns the screen — no newer link has arrived.
     *
     * Read after the seed has finished: by then a newer link may have arrived,
     * and that link owns the screen.
     */
    fun ownsScreen(arrival: Int): Boolean = arrival == arrivals
}

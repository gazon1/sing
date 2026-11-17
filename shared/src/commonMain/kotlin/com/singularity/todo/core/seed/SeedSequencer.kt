package com.singularity.todo.core.seed

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sequences the seed links that reach the debug activity.
 *
 * Three rules, one class:
 *
 * **One seed at a time.** A link that arrives while a seed is running waits
 * for it. A seed brings the profile to a defined state — a `reset=content`
 * wipes it — and two seeds writing at the same time leave a profile that is
 * neither one's state: rows written over each other, entities the other seed
 * never saw.
 *
 * **The newest link owns the screen.** A run that a newer link has superseded
 * says nothing: it does not bring the app up and it does not show its
 * failure. Ownership is decided by *arrival*, not by completion: a link that
 * arrived while the seed was running owns the screen even though it has not
 * started yet — it is the one that will write last.
 *
 * **A link is handled once.** The same link comes back through the activity's
 * resume right after its arrival was recorded, and that is not a second seed.
 */
class SeedSequencer {
    private val lock = Mutex()
    private var arrivals = 0
    private var lastLink: Any? = null

    /**
     * Registers a link that arrived, and returns its arrival number.
     *
     * Returns null when the link was handled already. The link is an opaque
     * token compared by identity: two deep links with the same text are two
     * links, and the same link twice is one link.
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

    /**
     * Runs the seed, one at a time.
     *
     * A caller that arrives while a seed is running waits for it to finish
     * before it starts. The lock is released when the seed fails, so a failed
     * seed does not block the next link.
     */
    suspend fun <T> run(block: suspend () -> T): T = lock.withLock { block() }
}

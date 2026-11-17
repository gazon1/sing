package com.singularity.todo.core.seed

import kotlinx.coroutines.CompletableDeferred
import kotlin.concurrent.Volatile

/**
 * Sequences the seed links that reach the debug activity.
 *
 * Three rules, one class:
 *
 * **One seed at a time, in arrival order.** A link that arrives while a seed is
 * running waits for it — and for every seed that arrived before it. The order is
 * the order of the *arrivals*, not the order in which the workers happen to
 * reach their gate: a worker that starts late still writes after the links that
 * arrived before it. A seed brings the profile to a defined state — a
 * `reset=content` wipes it — and two seeds writing at the same time leave a
 * profile that is neither one's state: rows written over each other, entities
 * the other seed never saw.
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
    /**
     * How many links have arrived. The newest one owns the screen.
     *
     * Written by the thread the links arrive on, read by the threads the seeds
     * run on — volatile, so a worker sees the arrival that superseded it.
     */
    @Volatile
    private var arrivals = 0

    /** The link that arrived last, compared by identity. */
    private var lastLink: Any? = null

    /** The gate of the newest arrival: the arrival before it waits for this. */
    private var tail: CompletableDeferred<Unit> = CompletableDeferred(Unit)

    /**
     * Registers a link that arrived, and returns its arrival.
     *
     * Returns null when the link was handled already. The link is an opaque
     * token compared by identity: two deep links with the same text are two
     * links, and the same link twice is one link.
     */
    fun arrive(link: Any): SeedArrival? {
        if (link === lastLink) return null
        lastLink = link
        arrivals += 1
        val own = CompletableDeferred<Unit>()
        val arrival = SeedArrival(arrivals, previous = tail, own = own)
        tail = own
        return arrival
    }

    /**
     * Whether the arrival still owns the screen — no newer link has arrived.
     *
     * Read after the seed has finished: by then a newer link may have arrived,
     * and that link owns the screen.
     */
    fun ownsScreen(arrival: SeedArrival): Boolean = arrival.number == arrivals

    /**
     * Runs the seed, one at a time, in arrival order.
     *
     * The seed of an arrival starts only after the seed of the arrival before it
     * has finished — not after some *other* seed that happened to start first. A
     * worker that arrives late therefore writes last, which is what the arrival
     * promised: the newest link owns the screen *and* has the last word on the
     * data.
     *
     * The gate is opened when the seed fails too, so a failed seed does not block
     * the links behind it.
     */
    suspend fun <T> run(
        arrival: SeedArrival,
        block: suspend () -> T,
    ): T {
        try {
            arrival.previous.join()
            return block()
        } finally {
            arrival.own.complete(Unit)
        }
    }
}

package com.singularity.todo.core.seed

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

/**
 * The queue the seed requests wait in.
 *
 * One channel, one consumer: the requests run one at a time, in the order they
 * arrived. A seed brings the profile to a defined state — a `reset=content`
 * wipes it — and two seeds writing at the same time leave a profile that is
 * neither one's state: rows written over each other, and a wipe that takes the
 * rows the other seed is still writing.
 *
 * The queue does not own the screen: [SeedSequencer] decides which arrival
 * does, and a run that was superseded stays quiet.
 */
class SeedQueue(private val sequencer: SeedSequencer) {
    /**
     * The requests waiting to be run.
     *
     * Unlimited: this is a debug-only tool, and a burst of links is a handful
     * of requests, not a stream of user events.
     */
    private val requests = Channel<SeedRequest>(Channel.UNLIMITED)

    /**
     * Puts a request in the queue.
     *
     * Returns false when the queue is closed — the consumer is gone and this
     * request will never run.
     */
    fun enqueue(request: SeedRequest): Boolean = requests.trySend(request).isSuccess

    /**
     * Closes the queue.
     *
     * The requests that are already in it still run — a seed that is waiting
     * must not lose its rows because the activity went away — and the consumer
     * stops when the queue is empty.
     */
    fun close() {
        requests.close()
    }

    /**
     * Runs the requests, one at a time, in arrival order.
     *
     * The consumer goes on after a seed fails: a failed seed is one link's
     * problem, and the links behind it have to be seeded anyway.
     *
     * The two callbacks are not symmetric on purpose:
     *
     * - [onSuccess] is called for the arrival that still owns the screen. A
     *   superseded run does not bring the app up: the launch that took the
     *   screen over is the one that does.
     * - [onFailure] is called for every failure. The diagnosis belongs to the
     *   seed that failed, whoever owns the screen; only the *showing* of it
     *   is the screen owner's business.
     */
    @Suppress("TooGenericExceptionCaught") // a seed fails in many ways; the failure belongs to that one seed
    suspend fun consume(
        seed: suspend (Map<String, String>) -> Unit,
        onSuccess: suspend (SeedRequest) -> Unit,
        onFailure: suspend (SeedRequest, Throwable) -> Unit,
    ) {
        for (request in requests) {
            try {
                seed(request.params)
            } catch (failure: CancellationException) {
                // The cancellation of the consumer is not a failed seed: it
                // has to travel on, or the queue would swallow it.
                throw failure
            } catch (failure: Exception) {
                onFailure(request, failure)
                continue
            }

            // Only the arrival that still owns the screen brings the app up.
            if (sequencer.ownsScreen(request.arrival)) onSuccess(request)
        }
    }
}

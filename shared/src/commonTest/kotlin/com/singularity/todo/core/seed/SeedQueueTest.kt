package com.singularity.todo.core.seed

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The queue the seed requests wait in.
 *
 * A seed brings the profile to a defined state — a `reset=content` wipes it —
 * and two seeds writing at the same time leave a profile that is neither
 * one's state. These tests hold the queue against the real consumer code — the
 * same [SeedQueue.consume] the activity runs — and not against a run number.
 */
@Tag("fast")
class SeedQueueTest {
    @Test
    fun `the second request waits for the first one to finish`() =
        runTest {
            val sequencer = SeedSequencer()
            val queue = SeedQueue(sequencer)
            val events = mutableListOf<String>()
            val firstMayFinish = CompletableDeferred<Unit>()

            val first = assertNotNull(sequencer.arrive(Any()))
            val second = assertNotNull(sequencer.arrive(Any()))

            launch {
                queue.consume(
                    seed = { params ->
                        events += "seed ${params["id"]} started"
                        if (params["id"] == "A") firstMayFinish.await()
                        events += "seed ${params["id"]} finished"
                    },
                    onSuccess = { request -> events += "success ${request.params["id"]}" },
                    onFailure = { request, _ -> events += "failure ${request.params["id"]}" },
                )
            }

            assertTrue(queue.enqueue(SeedRequest(first, mapOf("id" to "A"))))
            assertTrue(queue.enqueue(SeedRequest(second, mapOf("id" to "B"))))
            advanceUntilIdle()

            // The second request is in the queue, but the first seed is still
            // running: it must not have written anything yet.
            assertEquals(listOf("seed A started"), events)

            firstMayFinish.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "seed A started",
                    "seed A finished",
                    "seed B started",
                    "seed B finished",
                    "success B",
                ),
                events,
            )

            queue.close()
            advanceUntilIdle()
        }

    @Test
    fun `a superseded request does not bring the app up`() =
        runTest {
            val sequencer = SeedSequencer()
            val queue = SeedQueue(sequencer)
            val successes = mutableListOf<String>()
            val firstMayFinish = CompletableDeferred<Unit>()

            val first = assertNotNull(sequencer.arrive(Any()))
            val second = assertNotNull(sequencer.arrive(Any()))

            launch {
                queue.consume(
                    seed = { params ->
                        if (params["id"] == "A") firstMayFinish.await()
                    },
                    onSuccess = { request -> successes += request.params["id"].orEmpty() },
                    onFailure = { _, _ -> },
                )
            }

            queue.enqueue(SeedRequest(first, mapOf("id" to "A")))
            queue.enqueue(SeedRequest(second, mapOf("id" to "B")))
            advanceUntilIdle()

            // The second link arrived while the first seed was still running:
            // the first run no longer owns the screen.
            firstMayFinish.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("B"), successes)

            queue.close()
            advanceUntilIdle()
        }

    @Test
    fun `a failed request does not stop the queue`() =
        runTest {
            val sequencer = SeedSequencer()
            val queue = SeedQueue(sequencer)
            val events = mutableListOf<String>()

            val first = assertNotNull(sequencer.arrive(Any()))
            val second = assertNotNull(sequencer.arrive(Any()))

            launch {
                queue.consume(
                    seed = { params ->
                        if (params["id"] == "A") throw IllegalStateException("seed failed")
                        events += "seed ${params["id"]} ran"
                    },
                    onSuccess = { request -> events += "success ${request.params["id"]}" },
                    onFailure = { request, _ -> events += "failure ${request.params["id"]}" },
                )
            }

            queue.enqueue(SeedRequest(first, mapOf("id" to "A")))
            queue.enqueue(SeedRequest(second, mapOf("id" to "B")))
            advanceUntilIdle()

            // The failure is reported, and the request behind it still runs.
            assertEquals(listOf("failure A", "seed B ran", "success B"), events)

            queue.close()
            advanceUntilIdle()
        }

    @Test
    fun `a failure does not take the screen from a newer request`() =
        runTest {
            val sequencer = SeedSequencer()
            val queue = SeedQueue(sequencer)

            val first = assertNotNull(sequencer.arrive(Any()))
            val second = assertNotNull(sequencer.arrive(Any()))

            launch {
                queue.consume(
                    seed = { params ->
                        if (params["id"] == "A") throw IllegalStateException("seed failed")
                    },
                    onSuccess = { },
                    onFailure = { _, _ -> },
                )
            }

            queue.enqueue(SeedRequest(first, mapOf("id" to "A")))
            queue.enqueue(SeedRequest(second, mapOf("id" to "B")))
            advanceUntilIdle()

            // The failure belongs to the seed that failed; the screen stays
            // with the newest arrival.
            assertFalse(sequencer.ownsScreen(first))
            assertTrue(sequencer.ownsScreen(second))

            queue.close()
            advanceUntilIdle()
        }

    @Test
    fun `a closed queue takes no more requests`() =
        runTest {
            val sequencer = SeedSequencer()
            val queue = SeedQueue(sequencer)
            val arrival = assertNotNull(sequencer.arrive(Any()))

            queue.close()

            assertFalse(queue.enqueue(SeedRequest(arrival, mapOf("id" to "A"))))
        }

    @Test
    fun `the requests already in the queue still run after the queue is closed`() =
        runTest {
            val sequencer = SeedSequencer()
            val queue = SeedQueue(sequencer)
            val seeded = mutableListOf<String>()

            val arrival = assertNotNull(sequencer.arrive(Any()))

            launch {
                queue.consume(
                    seed = { params -> seeded += params["id"].orEmpty() },
                    onSuccess = { },
                    onFailure = { _, _ -> },
                )
            }

            queue.enqueue(SeedRequest(arrival, mapOf("id" to "A")))

            // The queue is closed while the request is still in it: a seed that
            // is waiting must not lose its rows because the activity went away.
            queue.close()
            advanceUntilIdle()

            assertEquals(listOf("A"), seeded)
        }
}

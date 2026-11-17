package com.singularity.todo.core.seed

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seeds run one at a time, and the newest link owns the screen.
 *
 * Two seed links can arrive at the debug activity while the first seed is
 * still running — it is `singleTask`, so the second link is delivered to the
 * live instance. Two seeds writing at the same time leave a profile that is
 * neither one's state: rows written over each other, entities the other seed
 * never saw, and a `reset=content` that wipes the rows the other seed is still
 * writing.
 *
 * These tests hold the sequence of two links against the real executor code —
 * the same `run` the activity calls — rather than against a run number.
 */
@Tag("fast")
class SeedSequencerTest {
    @Test
    fun `the second link waits for the first seed to finish`() =
        runTest {
            val sequencer = SeedSequencer()
            val events = mutableListOf<String>()
            val firstMayFinish = CompletableDeferred<Unit>()

            val first =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    sequencer.run {
                        events += "first started"
                        firstMayFinish.await()
                        events += "first finished"
                    }
                }
            val second =
                launch {
                    sequencer.run {
                        events += "second ran"
                    }
                }

            advanceUntilIdle()

            // The second link arrived while the first seed was still running:
            // it must not have written anything yet.
            assertEquals(listOf("first started"), events)

            firstMayFinish.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("first started", "first finished", "second ran"), events)
            assertTrue(first.isCompleted)
            assertTrue(second.isCompleted)
        }

    @Test
    fun `a failed seed does not block the next link`() =
        runTest {
            val sequencer = SeedSequencer()

            assertFailsWith<IllegalStateException> {
                sequencer.run { throw IllegalStateException("seed failed") }
            }

            var ran = false
            sequencer.run { ran = true }

            assertTrue(ran)
        }

    @Test
    fun `the newest link owns the screen`() {
        val sequencer = SeedSequencer()

        val first = assertNotNull(sequencer.arrive(Any()))
        val second = assertNotNull(sequencer.arrive(Any()))

        // Ownership is decided by arrival: the link that arrived last is the
        // one that will write last, so it owns the screen even though the
        // older run is the one that is still going.
        assertFalse(sequencer.ownsScreen(first))
        assertTrue(sequencer.ownsScreen(second))
    }

    @Test
    fun `the same link is handled once`() {
        val sequencer = SeedSequencer()
        val link = Any()

        val arrival = sequencer.arrive(link)

        assertNotNull(arrival)
        assertNull(sequencer.arrive(link))
    }
}

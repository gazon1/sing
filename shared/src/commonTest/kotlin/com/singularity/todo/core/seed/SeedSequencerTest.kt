package com.singularity.todo.core.seed

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The arrivals of the seed links, and the screen they own.
 *
 * Two seed links can arrive at the debug activity while the first seed is
 * still running — it is `singleTask`, so the second link is delivered to the
 * live instance. The sequencer counts those arrivals and says which of them
 * owns the screen; the order in which the seeds *run* is the queue's business
 * ([SeedQueueTest]).
 */
@Tag("fast")
class SeedSequencerTest {
    @Test
    fun `the same link is handled once`() {
        val sequencer = SeedSequencer()
        val link = Any()

        val arrival = sequencer.arrive(link)

        assertNotNull(arrival)
        assertNull(sequencer.arrive(link))
    }

    @Test
    fun `two links with the same uri are two arrivals`() {
        val sequencer = SeedSequencer()

        // Two deep links that carry the same text: the text is not the link,
        // the instance is.
        val first = sequencer.arrive(Link(uri = "todo-debug://seed?task=Buy%20milk"))
        val second = sequencer.arrive(Link(uri = "todo-debug://seed?task=Buy%20milk"))

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first + 1, second)
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
    fun `the previous arrival stops owning the screen`() {
        val sequencer = SeedSequencer()

        val first = assertNotNull(sequencer.arrive(Any()))

        assertTrue(sequencer.ownsScreen(first))

        sequencer.arrive(Any())

        assertFalse(sequencer.ownsScreen(first))
    }

    /** A link is an opaque token, compared by identity. */
    private data class Link(val uri: String)
}

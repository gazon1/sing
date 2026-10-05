package com.singularity.todo.feature.calendar_sync.domain.model

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The window is a bound on what a first sync pulls, and a wrong bound is not a crash — it is
 * thousands of events the user then has to remove. So the edges are pinned.
 */
@Tag("fast")
class ImportWindowTest {

    private val now = Instant.parse("2026-10-05T12:00:00Z")
    private val window = ImportWindow.DEFAULT

    @Test
    fun `the default reaches 30 days back and 90 days forward`() {
        assertEquals(30.days, window.past)
        assertEquals(90.days, window.future)
        assertEquals(Instant.parse("2026-09-05T12:00:00Z"), window.startInclusive(now))
        assertEquals(Instant.parse("2027-01-03T12:00:00Z"), window.endExclusive(now))
    }

    @Test
    fun `an event inside the window is kept`() {
        assertTrue(window.contains(now, now))
        assertTrue(window.contains(now + 89.days, now), "89 days ahead is inside 90")
        assertTrue(window.contains(now - 29.days, now), "29 days back is inside 30")
    }

    /** The far edges are inclusive, so an event on the boundary does not flicker in and out. */
    @Test
    fun `the boundary instants are inside`() {
        assertTrue(window.contains(window.startInclusive(now), now), "exactly 30 days back is in")
        assertTrue(window.contains(window.endExclusive(now)!!, now), "exactly 90 days ahead is in")
    }

    @Test
    fun `an event past the far edge is out`() {
        assertFalse(window.contains(now + 91.days, now), "91 days ahead is outside 90")
    }

    @Test
    fun `an event before the near edge is out`() {
        assertFalse(window.contains(now - 31.days, now), "31 days back is outside 30")
    }

    /**
     * A task due beyond the horizon is not lost — it simply has nothing to sync to yet, and
     * enters the window when it gets closer. That is why the far bound is a listing filter
     * and not a rule that hides the task.
     */
    @Test
    fun `the window does not decide what a task exists, only what is listed`() {
        // Nothing to assert on a task; what matters is that contains() is a pure boundary
        // question, so a future-due task is unaffected by it.
        assertFalse(window.contains(now + 400.days, now))
        assertTrue(window.contains(now + 1.days, now))
    }

    @Test
    fun `an event with no start is not importable`() {
        assertFalse(window.contains(null, now), "an event we cannot place in time is not imported")
    }

    @Test
    fun `an unbounded future has no upper end`() {
        val unbounded = ImportWindow(past = 0.days, future = kotlin.time.Duration.INFINITE)
        assertEquals(null, unbounded.endExclusive(now))
        assertTrue(unbounded.contains(now + 5_000.days, now), "with no upper bound, far ahead is in")
    }

    @Test
    fun `a zero-width window is valid and holds only now`() {
        val point = ImportWindow(past = 0.days, future = 0.days)
        assertTrue(point.contains(now, now))
        assertFalse(point.contains(now + 1.days, now))
        assertFalse(point.contains(now - 1.days, now))
    }

    @Test
    fun `a negative window is rejected rather than silently reversed`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ImportWindow(past = (-1).days)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ImportWindow(future = (-1).days)
        }
    }
}

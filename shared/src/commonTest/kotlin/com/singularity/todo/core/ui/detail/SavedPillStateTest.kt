package com.singularity.todo.core.ui.detail

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The pill both autosaving details show after an inline edit lands.
 *
 * These tests exist because the two screens used to own the timer themselves and both
 * copies were wrong in the same way: one uncancelled coroutine per pulse.
 *
 * The holder's collector lives as long as the scope it was given, and the test scope
 * waits for its children — so the scope here is the background one, which the test
 * cancels when it ends instead of waiting for.
 */
@Tag("fast")
class SavedPillStateTest {
    @Test
    fun `a pulse raises the pill and one window takes it down`() =
        runTest {
            val pill = SavedPillState(scope = backgroundScope)
            assertFalse(pill.visible, "nothing has been written yet, so there is nothing to say")

            pill.pulse()
            runCurrent()
            assertTrue(pill.visible, "the write that landed is what raises the pill")

            advanceTimeBy(1_500.milliseconds)
            runCurrent()
            assertFalse(pill.visible, "the pill is a moment, not a badge")
        }

    @Test
    fun `a second write inside the window keeps the pill up for its own full window`() =
        runTest {
            val pill = SavedPillState(scope = backgroundScope)
            pill.pulse()
            runCurrent()

            // The user paused for a moment and then typed again: the second debounced
            // write lands 200 ms after the first.
            advanceTimeBy(200.milliseconds)
            pill.pulse()
            runCurrent()
            assertTrue(pill.visible)

            // The first write's deadline. The pill must still be up: it is reporting the
            // second write now, and that write's window has not ended. The version this
            // replaced hid it here, so the newest save's indicator flickered off early.
            advanceTimeBy(1_300.milliseconds)
            runCurrent()
            assertTrue(pill.visible, "the older write's timer must not hide the newer write's pill")

            // A full window after the *last* write, and only then, it goes away.
            advanceTimeBy(1_000.milliseconds)
            runCurrent()
            assertFalse(pill.visible)
        }

    /**
     * The task detail cannot collect its own event flow — the events live in a channel with
     * one consumer, so the screen calls `pulse()` from that collector instead. Both routes
     * end in the same `pulse()`, which is the point of the holder; this pins it, so the two
     * screens cannot drift apart again the way their hand-rolled timers did.
     */
    @Test
    fun `an explicit pulse behaves exactly like one arriving from a flow`() =
        runTest {
            val pill = SavedPillState(scope = backgroundScope)

            repeat(3) {
                pill.pulse()
                runCurrent()
                assertTrue(pill.visible, "every pulse raises the pill, however it arrived")
                advanceTimeBy(1_500.milliseconds)
                runCurrent()
                assertFalse(pill.visible, "and one window after the last of them takes it down")
            }
        }
}

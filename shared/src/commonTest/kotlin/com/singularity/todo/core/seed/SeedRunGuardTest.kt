package com.singularity.todo.core.seed

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The newest seed run owns the screen.
 *
 * A second seed link can arrive while the first seed is still running. The
 * first run finishes later, and by then the second one has taken over: the
 * older run must not bring the app up and must not show its failure, or it
 * would undo the newer launch.
 */
@Tag("fast")
class SeedRunGuardTest {
    @Test
    fun `the first run owns the screen`() {
        val guard = SeedRunGuard()

        val run = guard.startRun()

        assertTrue(guard.ownsScreen(run))
    }

    @Test
    fun `a newer run takes the screen from the older one`() {
        val guard = SeedRunGuard()
        val first = guard.startRun()

        val second = guard.startRun()

        assertFalse(guard.ownsScreen(first))
        assertTrue(guard.ownsScreen(second))
    }

    @Test
    fun `the older run does not take the screen back when it finishes late`() {
        val guard = SeedRunGuard()
        val first = guard.startRun()
        val second = guard.startRun()

        // The older run finishes after the newer one started.
        val finishedFirst = guard.ownsScreen(first)

        assertFalse(finishedFirst)
        assertTrue(guard.ownsScreen(second))
    }
}

package com.singularity.todo.core.seed

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The link the debug activity is holding, through its three callbacks.
 *
 * The activity is `singleTask`, so a second link is delivered to the live
 * instance through `onNewIntent` — and the resume that follows it carries the
 * same link again. The links that were lost on the device were lost here: the
 * launch link was dropped when a second link arrived while the app was still
 * starting, and the second link was seeded twice when the resume ran it again.
 *
 * The activity's callbacks are three lines each; the behaviour they produce is
 * held here, where a test can hold it without an Android harness.
 */
@Tag("fast")
class SeedLinkLifecycleTest {
    @Test
    fun `a link that arrives while the first seed runs is seeded once, after it`() =
        runTest {
            val links = SeedLinkLifecycle<String>()
            val written = mutableListOf<String>()
            val firstMayFinish = CompletableDeferred<Unit>()

            // Cold start: the activity is started with a link.
            links.onCreate("A")
            val first = assertNotNull(links.arrive())

            val firstJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    links.run(first) {
                        written += "A"
                        firstMayFinish.await()
                    }
                }

            // The second link arrives at the live instance, before the first
            // seed has finished.
            links.onNewIntent("B")
            val second = assertNotNull(links.arrive())

            // The resume that follows the arrival is not a second seed of B.
            assertNull(links.arrive())

            val secondJob =
                launch {
                    links.run(second) {
                        written += "B"
                    }
                }
            advanceUntilIdle()

            // B waits for A: the seeds run in the order the links arrived, and
            // the launch link is not dropped by the link that superseded it.
            assertEquals(listOf("A"), written)

            firstMayFinish.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("A", "B"), written)
            // The newest link owns the screen; the older one keeps writing its
            // rows without taking the screen away from it.
            assertFalse(links.ownsScreen(first))
            assertTrue(links.ownsScreen(second))
            assertTrue(firstJob.isCompleted)
            assertTrue(secondJob.isCompleted)
        }
}

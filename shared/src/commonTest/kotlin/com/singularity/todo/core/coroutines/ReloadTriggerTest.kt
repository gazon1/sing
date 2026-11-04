package com.singularity.todo.core.coroutines

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for [ReloadTrigger] and [reloadOn].
 *
 * These are the tests the screens cannot write: they say what the trigger does
 * to a collection, with no repository and no ViewModel in the way. Every list
 * that observes a repository leans on these three properties, so a regression
 * here would show up as "the retry does nothing" on several screens at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("fast")
class ReloadTriggerTest {
    /**
     * A source that fails while [failure] is set and emits one value per
     * subscription otherwise.
     *
     * The subscription count is the observable a retry test needs: a retry that
     * only rewrote a state would leave the source unsubscribed a second time,
     * and nothing else here can tell the two apart.
     */
    private class Source {
        /** The failure the next subscription throws; null lets it emit. */
        var failure: Throwable? = null

        /** How many times the source was subscribed to. */
        var subscriptions: Int = 0
            private set

        fun flow(): Flow<Int> =
            flow {
                subscriptions++
                failure?.let { throw it }
                emit(subscriptions)
            }
    }

    @Test
    fun `reload counts the requests`() {
        val trigger = ReloadTrigger()

        assertEquals(0, trigger.ticks.value)

        trigger.reload()
        trigger.reload()

        assertEquals(2, trigger.ticks.value, "each reload is one more request, not a state")
    }

    /**
     * A failed attempt ends, and the cause reaches the caller's policy.
     *
     * "Ends" is the whole point: a collection that stopped did not merely get
     * an error value, it must leave the trigger usable.
     */
    @Test
    fun `a failure is reported with the original throwable`() =
        runTest {
            val failure = IllegalStateException("the observation failed")
            val source = Source().apply { this.failure = failure }
            val reported = mutableListOf<Throwable>()
            val emitted = mutableListOf<Int>()

            val trigger = ReloadTrigger()
            backgroundScope.launch {
                reloadOn(trigger, onFailure = { reported += it }, source = { source.flow() })
                    .collect { emitted += it }
            }
            runCurrent()

            assertEquals(1, reported.size)
            assertSame(failure, reported.single(), "the policy gets the throwable, not a message")
            assertTrue(emitted.isEmpty(), "an attempt that failed emits nothing for the screen to load")
        }

    /**
     * The retry re-subscribes — the reason this primitive exists.
     *
     * Before the failure handling moved inside the attempt, a Retry after a
     * failure bumped a trigger nobody was listening to: the collection had
     * ended with the exception it handled. This test fails on that shape,
     * because the second subscription never happens.
     */
    @Test
    fun `a reload after a failure subscribes again and emits`() =
        runTest {
            val source = Source().apply { failure = IllegalStateException("the observation failed") }
            val reported = mutableListOf<Throwable>()
            val emitted = mutableListOf<Int>()

            val trigger = ReloadTrigger()
            backgroundScope.launch {
                reloadOn(trigger, onFailure = { reported += it }, source = { source.flow() })
                    .collect { emitted += it }
            }
            runCurrent()

            assertEquals(1, source.subscriptions, "the first attempt subscribes once")

            // The second attempt can succeed, which is the only way the test can
            // tell "the retry ran" from "the screen stayed where it was".
            source.failure = null
            trigger.reload()
            runCurrent()

            assertEquals(2, source.subscriptions, "the retry must ask the source again")
            assertEquals(listOf(2), emitted, "the retried attempt's value reaches the collector")
            assertEquals(1, reported.size, "the failed attempt is reported once, not once per attempt")
        }

    /**
     * Every failed attempt is reported, not only the first.
     *
     * A retry that fails again must say so again: the second failure is the one
     * the user sees, and dropping it would leave the screen on a spinner that
     * nothing is going to answer.
     */
    @Test
    fun `a retry that fails again reports again and stops at that attempt`() =
        runTest {
            val first = IllegalStateException("the first attempt failed")
            val second = IllegalArgumentException("the retry failed too")
            val source = Source().apply { failure = first }
            val reported = mutableListOf<Throwable>()
            val emitted = mutableListOf<Int>()

            val trigger = ReloadTrigger()
            backgroundScope.launch {
                reloadOn(trigger, onFailure = { reported += it }, source = { source.flow() })
                    .collect { emitted += it }
            }
            runCurrent()

            source.failure = second
            trigger.reload()
            runCurrent()

            assertEquals(2, source.subscriptions)
            assertEquals(2, reported.size, "both failed attempts are reported")
            assertSame(first, reported[0])
            assertSame(second, reported[1], "the second report carries the second cause")
            assertTrue(emitted.isEmpty())
        }

    /**
     * A request made before the collection starts is not spent.
     *
     * The trigger is a [kotlinx.coroutines.flow.StateFlow] rather than an event
     * for this reason: a screen that retries while its collector is being set
     * up must still see a collection start.
     */
    @Test
    fun `a reload made before the collection starts still starts it`() =
        runTest {
            val source = Source()
            val emitted = mutableListOf<Int>()
            val trigger = ReloadTrigger()

            trigger.reload()
            trigger.reload()

            backgroundScope.launch {
                reloadOn(trigger, onFailure = {}, source = { source.flow() }).collect { emitted += it }
            }
            runCurrent()

            assertEquals(1, source.subscriptions, "the current count starts one attempt, not one per bump")
            assertEquals(listOf(1), emitted)
        }
}

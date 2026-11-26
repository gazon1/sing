package com.singularity.todo.feature.tasks.performance

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The arithmetic of the read-overhead measurement, checked without a clock.
 *
 * The harness publishes numbers that are the difference of two forms of a
 * read, and a percentile of a sample. Both are arithmetic, and arithmetic can
 * be checked exactly — while the latencies themselves cannot be, because a
 * shared machine has no fixed answer. So the arithmetic is checked here, and
 * the clock is left to the measurement.
 *
 * Three things are checked:
 *
 * 1. the percentile is the nearest rank of the sample, and an empty sample is
 *    not a number at all;
 * 2. the overhead of a read is the sum of its three parts **in every slot** —
 *    the identity the report publishes has to hold slot by slot, not only in
 *    the medians;
 * 3. two forms whose samples do not have the same count cannot be paired, and
 *    pairing them anyway would compare different slots silently.
 *
 * The order of the forms is checked as well: it is rotated from slot to slot
 * so that the position a form was measured in is not part of its price.
 */
@Tag("fast")
class ReadOverheadMeasurementTest {
    @Test
    fun `a percentile of an empty sample is not a number`() {
        assertFailsWith<IllegalStateException> {
            nearestRank(emptyList(), 0.5)
        }
    }

    @Test
    fun `the nearest rank of one sample is that sample`() {
        val sample = listOf(7.milliseconds)

        assertEquals(7.milliseconds, nearestRank(sample, 0.5))
        assertEquals(7.milliseconds, nearestRank(sample, 0.95))
    }

    @Test
    fun `the nearest rank is the value at the rank of the sorted sample`() {
        val sample = (1..100).map { it.milliseconds }

        assertEquals(50.milliseconds, nearestRank(sample, 0.5))
        assertEquals(95.milliseconds, nearestRank(sample, 0.95))
        assertEquals(1.milliseconds, nearestRank(sample, 0.0))
        assertEquals(100.milliseconds, nearestRank(sample, 1.0))
    }

    @Test
    fun `the overhead of a read is the sum of its three parts in every slot`() {
        val timings =
            FormTimings(
                statement = listOf(1.milliseconds, 2.milliseconds, 3.milliseconds),
                oneShot = listOf(2.milliseconds, 4.milliseconds, 6.milliseconds),
                plumbing = listOf(3.milliseconds, 6.milliseconds, 9.milliseconds),
                flow = listOf(4.milliseconds, 8.milliseconds, 12.milliseconds),
                sql = listOf(0.5.milliseconds, 1.milliseconds, 1.5.milliseconds),
                tracker = listOf(5.milliseconds),
            )

        assertEquals(
            listOf(3.milliseconds, 6.milliseconds, 9.milliseconds),
            timings.overhead,
            "the overhead is the flow over the statement",
        )
        assertEquals(timings.callPath.size, timings.overhead.size)
        timings.overhead.forEachIndexed { slot, overhead ->
            assertEquals(
                overhead,
                timings.callPath[slot] + timings.flowPlumbing[slot] + timings.invalidation[slot],
                "the three parts add up to the overhead in slot $slot",
            )
        }
    }

    @Test
    fun `two forms with different sample counts cannot be paired`() {
        val timings =
            FormTimings(
                statement = listOf(1.milliseconds, 2.milliseconds),
                oneShot = listOf(2.milliseconds),
                plumbing = listOf(3.milliseconds),
                flow = listOf(4.milliseconds),
                sql = listOf(0.5.milliseconds),
                tracker = listOf(5.milliseconds),
            )

        assertFailsWith<IllegalStateException> {
            timings.overhead
        }
    }

    @Test
    fun `the connection share is what the statement paid over the SQL work`() {
        val timings =
            FormTimings(
                statement = listOf(1.milliseconds, 2.milliseconds, 3.milliseconds),
                oneShot = listOf(2.milliseconds, 4.milliseconds, 6.milliseconds),
                plumbing = listOf(3.milliseconds, 6.milliseconds, 9.milliseconds),
                flow = listOf(4.milliseconds, 8.milliseconds, 12.milliseconds),
                sql = listOf(0.5.milliseconds, 1.milliseconds, 1.5.milliseconds),
                tracker = listOf(5.milliseconds),
            )

        assertEquals(
            listOf(0.5.milliseconds, 1.milliseconds, 1.5.milliseconds),
            timings.connection,
            "the connection share is the statement over the SQL work of the same slot",
        )
    }

    @Test
    fun `the order of the forms rotates so that every form sits in every position`() {
        val slots = 300
        val positions = ReadForm.entries.associateWith { form -> mutableListOf<Int>() }
        repeat(slots) { slot ->
            rotatedForms(slot).forEachIndexed { position, form ->
                val seen = positions.getValue(form)
                seen += position
            }
        }

        positions.forEach { (form, seen) ->
            assertEquals(slots, seen.size, "${form.label} was measured once per slot")
            ReadForm.entries.indices.forEach { position ->
                assertEquals(
                    slots / ReadForm.entries.size,
                    seen.count { it == position },
                    "${form.label} was measured in position $position equally often",
                )
            }
        }
    }

    @Test
    fun `the rotation is a shift of the published order`() {
        val published = ReadForm.entries.map { it.label }

        assertEquals(published, rotatedForms(0).map { it.label })
        assertEquals(published.drop(1) + published.take(1), rotatedForms(1).map { it.label })
        assertEquals(rotatedForms(0), rotatedForms(ReadForm.entries.size), "the order repeats every four slots")
        assertTrue(rotatedForms(0) != rotatedForms(1), "the order really moves")
    }

    @Test
    fun `the parts of a read are never longer than the sample they come from`() {
        val timings =
            FormTimings(
                statement = listOf(1.milliseconds, 2.milliseconds),
                oneShot = listOf(2.milliseconds, 3.milliseconds),
                plumbing = listOf(3.milliseconds, 4.milliseconds),
                flow = listOf(4.milliseconds, 5.milliseconds),
                sql = listOf(0.5.milliseconds, 1.milliseconds),
                tracker = listOf(5.milliseconds, 6.milliseconds),
            )

        val parts =
            listOf(
                timings.overhead,
                timings.callPath,
                timings.flowPlumbing,
                timings.invalidation,
            )
        parts.forEach { part ->
            assertEquals(timings.statement.size, part.size)
        }
        assertEquals(5.milliseconds, timings.observer)
    }
}

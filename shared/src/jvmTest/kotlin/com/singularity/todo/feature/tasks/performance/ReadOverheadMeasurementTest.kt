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
 * read. That difference is arithmetic, and arithmetic can be checked exactly —
 * while the latencies themselves cannot be, because a shared machine has no
 * fixed answer. So the arithmetic is checked here, and the clock is left to
 * the measurement.
 *
 * Three things are checked:
 *
 * 1. the overhead of a read is the sum of its three parts **in every slot** —
 *    the identity the report publishes has to hold slot by slot, not only in
 *    the medians;
 * 2. the difference of two forms is taken slot by slot — the first sample of
 *    one form against the first sample of the other — and two samples of
 *    different lengths cannot be paired, because pairing them anyway would
 *    compare different slots silently;
 * 3. the order of the forms rotates from slot to slot, so that the position a
 *    form was measured in is not part of its price.
 *
 * The percentile the harness publishes is arithmetic too, but it is shared by
 * every harness of the package and lives with them: see
 * [PerfBenchmarkSupportTest].
 */
@Tag("fast")
class ReadOverheadMeasurementTest {
    @Test
    fun `the difference of two forms is taken slot by slot`() {
        val left = listOf(10.milliseconds, 20.milliseconds, 30.milliseconds)
        val right = listOf(4.milliseconds, 8.milliseconds, 12.milliseconds)

        assertEquals(
            listOf(6.milliseconds, 12.milliseconds, 18.milliseconds),
            paired(left, right, "the two forms of one read"),
            "the first sample of one form is the first slot of the other",
        )
    }

    @Test
    fun `two samples of different lengths cannot be paired`() {
        val failure =
            assertFailsWith<IllegalStateException> {
                paired(
                    listOf(1.milliseconds, 2.milliseconds),
                    listOf(1.milliseconds),
                    "the two forms of one read",
                )
            }

        assertTrue(
            failure.message.orEmpty().contains("same slots"),
            "the failure has to say that the slots differ: ${failure.message}",
        )
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
                timings.callPath[slot] + timings.flowOperators[slot] + timings.invalidationSource[slot],
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
                timings.flowOperators,
                timings.invalidationSource,
            )
        parts.forEach { part ->
            assertEquals(timings.statement.size, part.size)
        }
        assertEquals(5.milliseconds, timings.observer)
    }
}

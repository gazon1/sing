package com.singularity.todo.feature.tasks.performance

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * The arithmetic every perf harness of this package shares, checked without a
 * clock.
 *
 * The harnesses publish a percentile of a sample of durations and that
 * duration in milliseconds. Both are arithmetic, and arithmetic can be checked
 * exactly — while the latencies themselves cannot be, because a shared
 * machine has no fixed answer. So the arithmetic is checked here, and the
 * clock is left to the measurement.
 *
 * The percentile is the nearest rank of the sample: the value at rank
 * `ceil(fraction × n)` of the sorted sample, with the rank held inside the
 * sample. The boundaries matter, because a percentile that walks off the end
 * of the sample is a number nobody measured:
 *
 * - an empty sample is not a number at all;
 * - a sample of one value is that value, whatever the fraction;
 * - an even sample has two middle values and takes the lower one, an odd
 *   sample has one and takes it;
 * - a fraction below the first rank still lands on the first value, and a
 *   fraction beyond the last still lands on the last.
 *
 * The order of the sample does not matter — the percentile is taken of the
 * sorted sample, so a harness that measured in a different order publishes
 * the same number.
 *
 * The load average is not checked here: it is the state of the machine, not
 * arithmetic — there is no fixed answer for it to be checked against.
 */
@Tag("fast")
class PerfBenchmarkSupportTest {
    @Test
    fun `a percentile of an empty sample is not a number`() {
        val failure =
            assertFailsWith<IllegalStateException> {
                nearestRank(emptyList(), 0.5)
            }

        assertTrue(
            failure.message.orEmpty().contains("not a number"),
            "the failure has to say that the sample held nothing: ${failure.message}",
        )
    }

    @Test
    fun `the nearest rank of one sample is that sample`() {
        val sample = listOf(7.milliseconds)

        assertEquals(7.milliseconds, nearestRank(sample, 0.5))
        assertEquals(7.milliseconds, nearestRank(sample, 0.95))
    }

    @Test
    fun `an even sample takes the lower of its two middle values`() {
        val sample = (1..4).map { it.milliseconds }

        assertEquals(2.milliseconds, nearestRank(sample, 0.5), "rank ceil(0.5 × 4) = 2")
        assertEquals(4.milliseconds, nearestRank(sample, 0.95), "rank ceil(0.95 × 4) = 4")
    }

    @Test
    fun `an odd sample takes its middle value`() {
        val sample = (1..5).map { it.milliseconds }

        assertEquals(3.milliseconds, nearestRank(sample, 0.5), "rank ceil(0.5 × 5) = 3")
        assertEquals(5.milliseconds, nearestRank(sample, 0.95), "rank ceil(0.95 × 5) = 5")
    }

    @Test
    fun `the nearest rank does not depend on the order of the sample`() {
        val sorted = (1..5).map { it.milliseconds }
        val shuffled = listOf(5, 3, 1, 4, 2).map { it.milliseconds }

        assertEquals(3.milliseconds, nearestRank(shuffled, 0.5))
        assertEquals(nearestRank(sorted, 0.5), nearestRank(shuffled, 0.5))
        assertEquals(nearestRank(sorted, 0.95), nearestRank(shuffled, 0.95))
    }

    @Test
    fun `a fraction below the first rank still lands on the first value`() {
        val sample = (1..10).map { it.milliseconds }

        assertEquals(1.milliseconds, nearestRank(sample, 0.01), "rank ceil(0.01 × 10) = 1")
        assertEquals(1.milliseconds, nearestRank(sample, 0.1), "rank ceil(0.1 × 10) = 1")
    }

    @Test
    fun `a fraction beyond the last rank still lands on the last value`() {
        val sample = (1..10).map { it.milliseconds }

        assertEquals(10.milliseconds, nearestRank(sample, 1.0), "rank ceil(1 × 10) = 10")
        assertEquals(10.milliseconds, nearestRank(sample, 1.5), "the rank is held inside the sample")
    }

    @Test
    fun `millis prints the milliseconds with two decimals`() {
        assertEquals("0.00", millis(0.microseconds))
        assertEquals("1.23", millis(1234.microseconds))
        assertEquals("1.50", millis(1500.microseconds))
        assertEquals("12.00", millis(12.milliseconds))
    }
}

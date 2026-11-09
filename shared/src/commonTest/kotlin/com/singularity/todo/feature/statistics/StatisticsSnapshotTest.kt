package com.singularity.todo.feature.statistics

import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The snapshot describes the data, not the moment it was computed.
 *
 * The snapshot used to carry the time it was computed at. That made two
 * computations of the same data unequal, so a state could never be compared
 * with what it had been before: every write looked like a change, and nothing
 * could tell "the numbers moved" from "the clock moved". The snapshot carries
 * no clock reading, and this pins that.
 */
@Tag("fast")
class StatisticsSnapshotTest {
    @Test
    fun `the same data computed at another moment compares equal`() {
        // Noon local time, so the two moments below cannot straddle midnight —
        // a day boundary would change the buckets for a reason that has
        // nothing to do with the snapshot.
        val zone = TimeZone.currentSystemDefault()
        val noon =
            Instant
                .fromEpochMilliseconds(System.currentTimeMillis())
                .toLocalDateTime(zone)
                .date
                .atTime(12, 0)
                .toInstant(zone)
                .toEpochMilliseconds()
        val completed = listOf("a" to (noon - 2 * 86_400_000L))
        val overdue = listOf("b" to (noon - 3 * 86_400_000L))

        val first = computeStatistics(completed, overdue, noon, 7)
        val later = computeStatistics(completed, overdue, noon + 3_600_000L, 7)

        assertEquals(first, later, "the same data must compute to the same snapshot")
    }
}

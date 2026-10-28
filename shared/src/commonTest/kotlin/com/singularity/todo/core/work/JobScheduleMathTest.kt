package com.singularity.todo.core.work

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * The arithmetic that decides when a job next runs.
 *
 * This is pure on purpose. No clock, no scope, no platform — so it can be tested exhaustively
 * in microseconds instead of through a scheduler and a virtual clock.
 *
 * The property that matters is **strictly after**. A "daily at 03:00" job that computed its
 * next boundary from a 03:00:00 clock would return 03:00:00 again; the loop wrapping this
 * would then spin forever rather than fail. Every boundary case below is that one.
 *
 * Tagged `fast` because it crosses no process boundary: no Compose harness, no file, no
 * database, no spawned executor. The rule is "slow means it leaves the JVM's arithmetic",
 * not "slow means it takes a while".
 */
@Tag("fast")
class JobScheduleMathTest {

    companion object {
        private val utc = TimeZone.UTC

        private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): LocalDateTime =
            LocalDate(year, month, day).atTime(hour, minute)

        @JvmStatic
        fun nextOccurrenceCases(): List<Arguments> = listOf(
            // ── Daily ────────────────────────────────────────────────────────
            Arguments.of(JobSchedule.Daily(3, 0), at(2026, 10, 6, 0, 5), at(2026, 10, 6, 3, 0)),
            // Strictly-after: equal is not "now", so it rolls to tomorrow.
            Arguments.of(JobSchedule.Daily(3, 0), at(2026, 10, 6, 3, 0), at(2026, 10, 7, 3, 0)),
            Arguments.of(JobSchedule.Daily(3, 0), at(2026, 10, 6, 23, 59), at(2026, 10, 7, 3, 0)),
            Arguments.of(JobSchedule.Daily(3, 0), at(2026, 10, 6, 2, 59), at(2026, 10, 6, 3, 0)),
            Arguments.of(JobSchedule.Daily(3, 0), at(2026, 10, 31, 4, 0), at(2026, 11, 1, 3, 0)),
            // ── Weekly ───────────────────────────────────────────────────────
            Arguments.of(JobSchedule.Weekly(DayOfWeek.MONDAY, 9, 0), at(2026, 10, 5, 8, 0), at(2026, 10, 5, 9, 0)),
            Arguments.of(JobSchedule.Weekly(DayOfWeek.MONDAY, 9, 0), at(2026, 10, 5, 9, 0), at(2026, 10, 12, 9, 0)),
            // Tuesday → next Monday is six days, not seven.
            Arguments.of(JobSchedule.Weekly(DayOfWeek.MONDAY, 9, 0), at(2026, 10, 6, 8, 0), at(2026, 10, 12, 9, 0)),
            Arguments.of(JobSchedule.Weekly(DayOfWeek.SUNDAY, 9, 0), at(2026, 10, 5, 10, 0), at(2026, 10, 11, 9, 0)),
            // ── No boundary ──────────────────────────────────────────────────
            Arguments.of(JobSchedule.OnDemand, at(2026, 10, 6, 12, 0), null),
            Arguments.of(JobSchedule.Periodic(Duration.parse("PT15M")), at(2026, 10, 6, 12, 0), null),
            // ── Calendar edges ───────────────────────────────────────────────
            Arguments.of(JobSchedule.Daily(3, 0), at(2026, 12, 31, 23, 0), at(2027, 1, 1, 3, 0)),
            Arguments.of(JobSchedule.Daily(3, 0), at(2028, 2, 29, 4, 0), at(2028, 3, 1, 3, 0)),
        )

        @JvmStatic
        fun weeklyCases(): List<Arguments> = listOf(
            Arguments.of(JobSchedule.Weekly(DayOfWeek.MONDAY, 9, 0), at(2026, 10, 5, 8, 0)),
            Arguments.of(JobSchedule.Weekly(DayOfWeek.MONDAY, 9, 0), at(2026, 10, 5, 9, 0)),
            Arguments.of(JobSchedule.Weekly(DayOfWeek.MONDAY, 9, 0), at(2026, 10, 6, 8, 0)),
            Arguments.of(JobSchedule.Weekly(DayOfWeek.SUNDAY, 9, 0), at(2026, 10, 5, 10, 0)),
        )

        @JvmStatic
        fun delayUntilCases(): List<Arguments> = listOf(
            Arguments.of(JobSchedule.OnDemand, at(2026, 10, 6, 12, 0), utc, true),
            Arguments.of(JobSchedule.Daily(3, 0), at(2026, 10, 6, 3, 0), utc, false),
            Arguments.of(JobSchedule.Daily(3, 0), at(2026, 10, 6, 2, 59), utc, false),
            Arguments.of(JobSchedule.Daily(3, 0), at(2026, 10, 6, 23, 59), utc, false),
            Arguments.of(JobSchedule.Daily(3, 0), at(2026, 10, 6, 0, 0), utc, false),
            Arguments.of(JobSchedule.Weekly(DayOfWeek.WEDNESDAY, 3, 0), at(2026, 10, 6, 3, 0), utc, false),
            Arguments.of(JobSchedule.Weekly(DayOfWeek.WEDNESDAY, 3, 0), at(2026, 10, 6, 2, 59), utc, false),
            Arguments.of(JobSchedule.Weekly(DayOfWeek.WEDNESDAY, 3, 0), at(2026, 10, 6, 23, 59), utc, false),
            Arguments.of(JobSchedule.Weekly(DayOfWeek.WEDNESDAY, 3, 0), at(2026, 10, 6, 0, 0), utc, false),
            // Europe/Berlin springs forward on 2026-03-29; a 03:00 daily job that lands on
            // the skipped hour must still produce a usable delay.
            Arguments.of(
                JobSchedule.Daily(3, 0),
                at(2026, 3, 29, 1, 30),
                TimeZone.of("Europe/Berlin"),
                false,
            ),
        )
    }

    @ParameterizedTest
    @MethodSource("nextOccurrenceCases")
    fun `next occurrence is strictly after the given instant`(
        schedule: JobSchedule,
        now: LocalDateTime,
        expected: LocalDateTime?,
    ) {
        assertEquals(expected, JobScheduleMath.nextOccurrence(schedule, now))
    }

    @ParameterizedTest
    @MethodSource("delayUntilCases")
    fun `delay until the boundary is zero only when there is no boundary`(
        schedule: JobSchedule,
        now: LocalDateTime,
        zone: TimeZone,
        expectZero: Boolean,
    ) {
        val delay = JobScheduleMath.delayUntil(schedule, now, zone)
        if (expectZero) {
            assertEquals(Duration.ZERO, delay)
        } else {
            assertTrue(delay > Duration.ZERO, "$schedule at $now produced $delay — a non-positive delay would spin")
        }
    }

    @ParameterizedTest
    @MethodSource("weeklyCases")
    fun `a weekly next occurrence lands on the requested weekday`(
        schedule: JobSchedule.Weekly,
        now: LocalDateTime,
    ) {
        val next = JobScheduleMath.nextOccurrence(schedule, now)
        assertEquals(schedule.dayOfWeek, next?.date?.dayOfWeek, "wrong weekday for $now")
        assertTrue(next != null && next >= now, "next occurrence $next is not after $now")
    }

    @Test
    fun `a weekly next occurrence stays on the weekday across repeated hops`() {
        val target = DayOfWeek.THURSDAY
        var now = at(2026, 10, 6, 12, 0)
        repeat(10) {
            val next = JobScheduleMath.nextOccurrence(JobSchedule.Weekly(target, 3, 0), now)!!
            assertEquals(target, next.date.dayOfWeek, "wrong weekday for $now")
            assertTrue(next >= now, "next occurrence $next is not after $now")
            now = next
        }
    }
}

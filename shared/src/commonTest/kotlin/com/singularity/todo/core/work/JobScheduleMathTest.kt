package com.singularity.todo.core.work

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus

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
 * Tagged `fast`: pure arithmetic with no clock, no scope and no platform, so it belongs with
 * the tests that run on every change. An untagged class is silently excluded from CI, which
 * runs `-Ptest.tags=fast,slow` — the failure mode TestTagCoverageTest exists to prevent.
 */
@Tag("fast")
class JobScheduleMathTest {

    private val utc = TimeZone.UTC

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0) =
        LocalDate(year, month, day).atTime(hour, minute)

    // ── Daily ──────────────────────────────────────────────────────────

    @Test
    fun `daily later today returns today`() {
        val next = JobScheduleMath.nextOccurrence(
            JobSchedule.Daily(3, 0),
            at(2026, 10, 6, 0, 5),
        )
        assertEquals(at(2026, 10, 6, 3, 0), next)
    }

    @Test
    fun `daily at exactly this instant rolls to tomorrow`() {
        // The spin case. Strictly-after means equal is not "now".
        val next = JobScheduleMath.nextOccurrence(
            JobSchedule.Daily(3, 0),
            at(2026, 10, 6, 3, 0),
        )
        assertEquals(at(2026, 10, 7, 3, 0), next)
    }

    @Test
    fun `daily crossing midnight rolls to tomorrow`() {
        val next = JobScheduleMath.nextOccurrence(
            JobSchedule.Daily(3, 0),
            at(2026, 10, 6, 23, 59),
        )
        assertEquals(at(2026, 10, 7, 3, 0), next)
    }

    @Test
    fun `daily one minute before fires today`() {
        val next = JobScheduleMath.nextOccurrence(
            JobSchedule.Daily(3, 0),
            at(2026, 10, 6, 2, 59),
        )
        assertEquals(at(2026, 10, 6, 3, 0), next)
    }

    @Test
    fun `daily rolls over a month boundary`() {
        val next = JobScheduleMath.nextOccurrence(
            JobSchedule.Daily(3, 0),
            at(2026, 10, 31, 4, 0),
        )
        assertEquals(at(2026, 11, 1, 3, 0), next)
    }

    // ── Weekly ─────────────────────────────────────────────────────────

    @Test
    fun `weekly later today returns today`() {
        val monday = at(2026, 10, 5, 8, 0) // 2026-10-05 is a Monday
        val next = JobScheduleMath.nextOccurrence(
            JobSchedule.Weekly(kotlinx.datetime.DayOfWeek.MONDAY, 9, 0),
            monday,
        )
        assertEquals(at(2026, 10, 5, 9, 0), next)
    }

    @Test
    fun `weekly at exactly this instant rolls seven days`() {
        val next = JobScheduleMath.nextOccurrence(
            JobSchedule.Weekly(kotlinx.datetime.DayOfWeek.MONDAY, 9, 0),
            at(2026, 10, 5, 9, 0),
        )
        assertEquals(at(2026, 10, 12, 9, 0), next)
    }

    @Test
    fun `weekly on a different day counts forward`() {
        // Tuesday → next Monday is six days, not seven.
        val next = JobScheduleMath.nextOccurrence(
            JobSchedule.Weekly(kotlinx.datetime.DayOfWeek.MONDAY, 9, 0),
            at(2026, 10, 6, 8, 0),
        )
        assertEquals(at(2026, 10, 12, 9, 0), next)
    }

    @Test
    fun `weekly one day past the target wraps the week`() {
        val next = JobScheduleMath.nextOccurrence(
            JobSchedule.Weekly(kotlinx.datetime.DayOfWeek.SUNDAY, 9, 0),
            at(2026, 10, 5, 10, 0),
        )
        assertEquals(at(2026, 10, 11, 9, 0), next)
    }

    // ── Schedules with no boundary ─────────────────────────────────────

    @Test
    fun `on-demand and periodic have no next occurrence`() {
        val now = at(2026, 10, 6, 12, 0)
        assertNull(JobScheduleMath.nextOccurrence(JobSchedule.OnDemand, now))
        assertNull(
            JobScheduleMath.nextOccurrence(
                JobSchedule.Periodic(kotlin.time.Duration.parse("PT15M")),
                now,
            ),
        )
    }

    @Test
    fun `delayUntil is zero when there is no boundary to wait for`() {
        val now = at(2026, 10, 6, 12, 0)
        assertEquals(
            kotlin.time.Duration.ZERO,
            JobScheduleMath.delayUntil(JobSchedule.OnDemand, now, utc),
        )
    }

    // ── Delay ──────────────────────────────────────────────────────────

    @Test
    fun `delayUntil is always positive`() {
        val schedules = listOf(
            JobSchedule.Daily(3, 0),
            JobSchedule.Weekly(kotlinx.datetime.DayOfWeek.WEDNESDAY, 3, 0),
        )
        val nows = listOf(
            at(2026, 10, 6, 3, 0),
            at(2026, 10, 6, 2, 59),
            at(2026, 10, 6, 23, 59),
            at(2026, 10, 6, 0, 0),
        )
        schedules.forEach { schedule ->
            nows.forEach { now ->
                val delay = JobScheduleMath.delayUntil(schedule, now, utc)
                assertTrue(
                    delay > kotlin.time.Duration.ZERO,
                    "$schedule at $now produced $delay — a non-positive delay would spin",
                )
            }
        }
    }

    @Test
    fun `delay across a spring-forward day stays positive`() {
        // Europe/Berlin springs forward on 2026-03-29. A 03:00 daily job that lands on the
        // skipped hour must still produce a usable delay rather than a negative one.
        val berlin = TimeZone.of("Europe/Berlin")
        val now = LocalDate(2026, 3, 29).atTime(1, 30)
        val delay = JobScheduleMath.delayUntil(JobSchedule.Daily(3, 0), now, berlin)
        assertTrue(delay > kotlin.time.Duration.ZERO, "DST transition produced $delay")
    }

    @Test
    fun `next occurrence always lands on the requested weekday`() {
        val target = kotlinx.datetime.DayOfWeek.THURSDAY
        var now = at(2026, 10, 6, 12, 0)
        repeat(10) {
            val next = JobScheduleMath.nextOccurrence(JobSchedule.Weekly(target, 3, 0), now)
            assertEquals(target, next?.date?.dayOfWeek, "wrong weekday for $now")
            assertTrue(next != null && next >= now, "next occurrence $next is not after $now")
            now = next!!
        }
    }

    @Test
    fun `a date far ahead does not overflow the year`() {
        val now = at(2026, 12, 31, 23, 0)
        val next = JobScheduleMath.nextOccurrence(JobSchedule.Daily(3, 0), now)
        assertEquals(at(2027, 1, 1, 3, 0), next)
    }

    @Test
    fun `plus one day from a leap day lands on the first of March`() {
        val leapDay = LocalDate(2028, 2, 29).atTime(4, 0)
        val next = JobScheduleMath.nextOccurrence(JobSchedule.Daily(3, 0), leapDay)
        assertEquals(LocalDate(2028, 3, 1).atTime(3, 0), next)
        assertEquals(LocalDate(2028, 3, 1), leapDay.date.plus(1, DateTimeUnit.DAY))
    }
}

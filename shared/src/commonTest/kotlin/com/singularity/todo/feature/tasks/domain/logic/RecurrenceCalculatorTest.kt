package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Monthly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Weekly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Yearly
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class RecurrenceCalculatorTest {

    private fun d(y: Int, m: Int, day: Int) = LocalDate(y, m, day)

    // ─── nextOccurrence — Interval ─────────────────────────────────────────────

    @Test
    fun `Interval DAY advances by that many days`() {
        val spec = Interval(RecurrenceBase.FROM_COMPLETION, 1, DateTimeUnit.DAY)
        assertEquals(d(2026, 1, 16), RecurrenceCalculator.nextOccurrence(spec, d(2026, 1, 15)))
    }

    @Test
    fun `Interval WEEK advances by that many weeks`() {
        val spec = Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.WEEK)
        assertEquals(d(2026, 1, 22), RecurrenceCalculator.nextOccurrence(spec, d(2026, 1, 15)))
    }

    @Test
    fun `Interval 2 WEEKS advances by 2 weeks`() {
        val spec = Interval(RecurrenceBase.FROM_DUE, 2, DateTimeUnit.WEEK)
        assertEquals(d(2026, 1, 29), RecurrenceCalculator.nextOccurrence(spec, d(2026, 1, 15)))
    }

    @Test
    fun `Interval MONTH advances to same day next month`() {
        val spec = Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.MONTH)
        assertEquals(d(2026, 2, 15), RecurrenceCalculator.nextOccurrence(spec, d(2026, 1, 15)))
    }

    @Test
    fun `Interval YEAR advances by 1 year`() {
        val spec = Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.YEAR)
        assertEquals(d(2027, 1, 15), RecurrenceCalculator.nextOccurrence(spec, d(2026, 1, 15)))
    }

    // ─── nextOccurrence — Weekly ─────────────────────────────────────────────────

    @Test
    fun `Weekly finds next weekday in same week`() {
        val spec = Weekly(RecurrenceBase.FROM_DUE, setOf(1, 3, 5)) // Mon, Wed, Fri
        // Friday Jan 16 → next is Monday Jan 19 (but anchor is already Fri, next strictly after)
        // Actually: anchor is Fri, sorted=[1,3,5], next after 5 = null → wrap to 1+7=8 → 1+7=8
        // anchorDow=5, next=null, firstNextWeek=1+7=8 → 8-5=3 days
        assertEquals(d(2026, 1, 19), RecurrenceCalculator.nextOccurrence(spec, d(2026, 1, 16)))
    }

    @Test
    fun `Weekly finds later weekday in same week`() {
        val spec = Weekly(RecurrenceBase.FROM_DUE, setOf(3, 5)) // Wed, Fri
        // Wednesday Jan 14 → next is Fri Jan 16
        assertEquals(d(2026, 1, 16), RecurrenceCalculator.nextOccurrence(spec, d(2026, 1, 14)))
    }

    @Test
    fun `Weekly wraps to next week`() {
        val spec = Weekly(RecurrenceBase.FROM_DUE, setOf(2, 4)) // Tue, Thu
        // Thursday Jan 15 → next is Tue Jan 20
        assertEquals(d(2026, 1, 20), RecurrenceCalculator.nextOccurrence(spec, d(2026, 1, 15)))
    }

    // ─── nextOccurrence — Monthly ───────────────────────────────────────────────

    @Test
    fun `Monthly day 15 goes to Feb 15`() {
        val spec = Monthly(RecurrenceBase.FROM_DUE, 15)
        assertEquals(d(2026, 2, 15), RecurrenceCalculator.nextOccurrence(spec, d(2026, 1, 15)))
    }

    @Test
    fun `Monthly day 31 caps at February`() {
        val spec = Monthly(RecurrenceBase.FROM_DUE, 31)
        // Feb has 28 days → Jan 31 + 1 month = Feb 28
        assertEquals(d(2026, 2, 28), RecurrenceCalculator.nextOccurrence(spec, d(2026, 1, 31)))
    }

    // ─── nextOccurrence — Yearly ────────────────────────────────────────────────

    @Test
    fun `Yearly advances to next year same month and day`() {
        val spec = Yearly(RecurrenceBase.FROM_DUE, 1, 15)
        assertEquals(d(2027, 1, 15), RecurrenceCalculator.nextOccurrence(spec, d(2026, 1, 15)))
    }

    // ─── missedCount ────────────────────────────────────────────────────────────

    @Test
    fun `missedCount returns 0 when today is at or before anchor`() {
        val spec = Interval(RecurrenceBase.CATCH_UP, 1, DateTimeUnit.DAY)
        assertEquals(0, RecurrenceCalculator.missedCount(spec, d(2026, 1, 15), d(2026, 1, 15)))
        assertEquals(0, RecurrenceCalculator.missedCount(spec, d(2026, 1, 16), d(2026, 1, 15)))
    }

    @Test
    fun `missedCount counts days between anchor and today`() {
        val spec = Interval(RecurrenceBase.CATCH_UP, 1, DateTimeUnit.DAY)
        // Jan 10 to Jan 15: missed 4 days (Jan 11, 12, 13, 14, 15 → actually 4 intervals)
        // anchor=10, today=15: next=11,12,13,14,15 → 5 occurrences?
        // countIntervalMissed: while next <= today { count++; current=next }
        // Jan 10 anchor, Jan 15 today:
        // next=11 (<=15, count=1), current=11
        // next=12 (<=15, count=2), current=12
        // next=13 (<=15, count=3), current=13
        // next=14 (<=15, count=4), current=14
        // next=15 (<=15, count=5), current=15
        // next=16 (>15, break) → 5
        assertEquals(5, RecurrenceCalculator.missedCount(spec, d(2026, 1, 10), d(2026, 1, 15)))
    }

    @Test
    fun `missedCount caps at MAX_MISSED`() {
        val spec = Interval(RecurrenceBase.CATCH_UP, 1, DateTimeUnit.DAY)
        // 365 days → capped at 10
        assertEquals(10, RecurrenceCalculator.missedCount(spec, d(2026, 1, 1), d(2026, 12, 31)))
    }

    @Test
    fun `missedCount weekly counts weeks`() {
        val spec = Interval(RecurrenceBase.CATCH_UP, 1, DateTimeUnit.WEEK)
        // Jan 1 to Feb 1: ~4 weeks
        val count = RecurrenceCalculator.missedCount(spec, d(2026, 1, 1), d(2026, 2, 1))
        assertEquals(4, count)
    }
}

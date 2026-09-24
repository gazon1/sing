package com.singularity.todo.feature.tasks.presentation.components

import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Monthly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Weekly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Yearly
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class RecurrenceFormattersTest {

    private fun d(y: Int, m: Int, day: Int) = LocalDate(y, m, day)

    // ─── label ─────────────────────────────────────────────────────────────────

    @Test
    fun `label FROM_COMPLETION shows no arrow`() {
        val spec = Interval(RecurrenceBase.FROM_COMPLETION, 1, DateTimeUnit.WEEK)
        assertEquals("Weekly", RecurrenceFormatters.label(spec))
    }

    @Test
    fun `label FROM_DUE shows up arrow`() {
        val spec = Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.WEEK)
        assertEquals("↑ Weekly", RecurrenceFormatters.label(spec))
    }

    @Test
    fun `label CATCH_UP shows refresh arrow`() {
        val spec = Interval(RecurrenceBase.CATCH_UP, 1, DateTimeUnit.WEEK)
        assertEquals("↺ Weekly", RecurrenceFormatters.label(spec))
    }

    @Test
    fun `label 2 weeks shows amount`() {
        val spec = Interval(RecurrenceBase.FROM_COMPLETION, 2, DateTimeUnit.WEEK)
        assertEquals("Every 2 weeks", RecurrenceFormatters.label(spec))
    }

    @Test
    fun `label daily shows Daily`() {
        val spec = Interval(RecurrenceBase.FROM_COMPLETION, 1, DateTimeUnit.DAY)
        assertEquals("Daily", RecurrenceFormatters.label(spec))
    }

    @Test
    fun `label monthly shows Monthly`() {
        val spec = Interval(RecurrenceBase.FROM_COMPLETION, 1, DateTimeUnit.MONTH)
        assertEquals("Monthly", RecurrenceFormatters.label(spec))
    }

    @Test
    fun `label yearly shows Yearly`() {
        val spec = Interval(RecurrenceBase.FROM_COMPLETION, 1, DateTimeUnit.YEAR)
        assertEquals("Yearly", RecurrenceFormatters.label(spec))
    }

    @Test
    fun `label Weekly shows weekday list`() {
        val spec = Weekly(RecurrenceBase.FROM_DUE, setOf(1, 3, 5))
        assertEquals("↑ Weekly(Mon,Wed,Fri)", RecurrenceFormatters.label(spec))
    }

    @Test
    fun `label Monthly shows day number`() {
        val spec = Monthly(RecurrenceBase.FROM_DUE, 15)
        assertEquals("↑ Monthly(15)", RecurrenceFormatters.label(spec))
    }

    @Test
    fun `label Yearly shows month and day`() {
        val spec = Yearly(RecurrenceBase.FROM_DUE, 12, 25)
        assertEquals("↑ Yearly(December 25)", RecurrenceFormatters.label(spec))
    }

    // ─── baseLabel ──────────────────────────────────────────────────────────────

    @Test
    fun `baseLabel returns human-readable strings`() {
        assertEquals("From due date", RecurrenceFormatters.baseLabel(RecurrenceBase.FROM_DUE))
        assertEquals("From completion", RecurrenceFormatters.baseLabel(RecurrenceBase.FROM_COMPLETION))
        assertEquals("Catch-up (fill gaps)", RecurrenceFormatters.baseLabel(RecurrenceBase.CATCH_UP))
    }

    // ─── nextOccurrencePreview ─────────────────────────────────────────────────

    @Test
    fun `nextOccurrencePreview shows next date`() {
        val spec = Interval(RecurrenceBase.FROM_COMPLETION, 1, DateTimeUnit.WEEK)
        val preview = RecurrenceFormatters.nextOccurrencePreview(d(2026, 1, 15), spec)
        assertEquals("Next: 2026-01-22", preview)
    }

    @Test
    fun `nextOccurrencePreview monthly shows first of next month`() {
        val spec = Monthly(RecurrenceBase.FROM_DUE, 1)
        val preview = RecurrenceFormatters.nextOccurrencePreview(d(2026, 1, 1), spec)
        assertEquals("Next: 2026-02-01", preview)
    }
}

package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Monthly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Weekly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Yearly
import kotlinx.datetime.DateTimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RecurrenceParserTest {

    // ─── Short form ─────────────────────────────────────────────────────────────

    @Test
    fun `+1d parses as FROM_COMPLETION 1 DAY`() {
        val r = RecurrenceParser.parse("+1d")
        assertEquals(Interval(RecurrenceBase.FROM_COMPLETION, 1, DateTimeUnit.DAY), r)
    }

    @Test
    fun `+1w parses as FROM_COMPLETION 1 WEEK`() {
        val r = RecurrenceParser.parse("+1w")
        assertEquals(Interval(RecurrenceBase.FROM_COMPLETION, 1, DateTimeUnit.WEEK), r)
    }

    @Test
    fun `+1m parses as FROM_COMPLETION 1 MONTH`() {
        val r = RecurrenceParser.parse("+1m")
        assertEquals(Interval(RecurrenceBase.FROM_COMPLETION, 1, DateTimeUnit.MONTH), r)
    }

    @Test
    fun `+1y parses as FROM_COMPLETION 1 YEAR`() {
        val r = RecurrenceParser.parse("+1y")
        assertEquals(Interval(RecurrenceBase.FROM_COMPLETION, 1, DateTimeUnit.YEAR), r)
    }

    @Test
    fun `++1w parses as FROM_DUE 1 WEEK`() {
        val r = RecurrenceParser.parse("++1w")
        assertEquals(Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.WEEK), r)
    }

    @Test
    fun `++1m parses as FROM_DUE 1 MONTH`() {
        val r = RecurrenceParser.parse("++1m")
        assertEquals(Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.MONTH), r)
    }

    @Test
    fun `!+1w parses as CATCH_UP 1 WEEK`() {
        val r = RecurrenceParser.parse("!+1w")
        assertEquals(Interval(RecurrenceBase.CATCH_UP, 1, DateTimeUnit.WEEK), r)
    }

    @Test
    fun `dot-plus-1m parses as FROM_COMPLETION with explicit dot`() {
        val r = RecurrenceParser.parse(".+1m")
        assertEquals(Interval(RecurrenceBase.FROM_COMPLETION, 1, DateTimeUnit.MONTH), r)
    }

    @Test
    fun `dot-plus-plus-1m parses as FROM_DUE with explicit dot`() {
        val r = RecurrenceParser.parse(".++1m")
        assertEquals(Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.MONTH), r)
    }

    @Test
    fun `+2w parses as 2 weeks`() {
        val r = RecurrenceParser.parse("+2w")
        assertEquals(Interval(RecurrenceBase.FROM_COMPLETION, 2, DateTimeUnit.WEEK), r)
    }

    // ─── Every weekday ───────────────────────────────────────────────────────────

    @Test
    fun `every Mon,Wed,Fri parses as Weekly FROM_DUE`() {
        val r = RecurrenceParser.parse("every Mon,Wed,Fri")
        assertEquals(Weekly(RecurrenceBase.FROM_DUE, setOf(1, 3, 5)), r)
    }

    @Test
    fun `every Monday parses as Weekly with single weekday`() {
        val r = RecurrenceParser.parse("every Monday")
        assertEquals(Weekly(RecurrenceBase.FROM_DUE, setOf(1)), r)
    }

    @Test
    fun `every Sunday parses as Weekly with 7`() {
        val r = RecurrenceParser.parse("every Sunday")
        assertEquals(Weekly(RecurrenceBase.FROM_DUE, setOf(7)), r)
    }

    @Test
    fun `every SATURDAY is case-insensitive`() {
        val r = RecurrenceParser.parse("every SATURDAY")
        assertEquals(Weekly(RecurrenceBase.FROM_DUE, setOf(6)), r)
    }

    // ─── Every interval ─────────────────────────────────────────────────────────

    @Test
    fun `every week parses as FROM_DUE 1 WEEK`() {
        val r = RecurrenceParser.parse("every week")
        assertEquals(Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.WEEK), r)
    }

    @Test
    fun `every 2 weeks parses as FROM_DUE 2 WEEK`() {
        val r = RecurrenceParser.parse("every 2 weeks")
        assertEquals(Interval(RecurrenceBase.FROM_DUE, 2, DateTimeUnit.WEEK), r)
    }

    @Test
    fun `every 3 days parses as FROM_DUE 3 DAY`() {
        val r = RecurrenceParser.parse("every 3 days")
        assertEquals(Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY), r)
    }

    @Test
    fun `every month parses as FROM_DUE 1 MONTH`() {
        val r = RecurrenceParser.parse("every month")
        assertEquals(Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.MONTH), r)
    }

    @Test
    fun `every year parses as FROM_DUE 1 YEAR`() {
        val r = RecurrenceParser.parse("every year")
        assertEquals(Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.YEAR), r)
    }

    // ─── Monthly ─────────────────────────────────────────────────────────────────

    @Test
    fun `1st of month parses as Monthly FROM_DUE day 1`() {
        val r = RecurrenceParser.parse("1st of month")
        assertEquals(Monthly(RecurrenceBase.FROM_DUE, 1), r)
    }

    @Test
    fun `15th of month parses as Monthly FROM_DUE day 15`() {
        val r = RecurrenceParser.parse("15th of month")
        assertEquals(Monthly(RecurrenceBase.FROM_DUE, 15), r)
    }

    @Test
    fun `22nd of month parses as Monthly FROM_DUE day 22`() {
        val r = RecurrenceParser.parse("22nd of month")
        assertEquals(Monthly(RecurrenceBase.FROM_DUE, 22), r)
    }

    // ─── Yearly ─────────────────────────────────────────────────────────────────

    @Test
    fun `every Jan 1 parses as Yearly FROM_DUE January 1`() {
        val r = RecurrenceParser.parse("every Jan 1")
        assertEquals(Yearly(RecurrenceBase.FROM_DUE, 1, 1), r)
    }

    @Test
    fun `every December 25 parses as Yearly FROM_DUE December 25`() {
        val r = RecurrenceParser.parse("every December 25")
        assertEquals(Yearly(RecurrenceBase.FROM_DUE, 12, 25), r)
    }

    // ─── Errors ─────────────────────────────────────────────────────────────────

    @Test
    fun `blank throws`() {
        assertFailsWith<IllegalArgumentException> {
            RecurrenceParser.parse("   ")
        }
    }

    @Test
    fun `unknown pattern throws`() {
        assertFailsWith<IllegalArgumentException> {
            RecurrenceParser.parse("hello world")
        }
    }

    @Test
    fun `invalid unit throws`() {
        assertFailsWith<IllegalArgumentException> {
            RecurrenceParser.parse("+1x")
        }
    }
}

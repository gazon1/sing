package com.singularity.todo.feature.tasks.domain

import com.singularity.todo.feature.tasks.domain.logic.RecurrenceParser
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceTermination
import com.singularity.todo.feature.tasks.domain.model.isExhaustedBy
import com.singularity.todo.feature.tasks.domain.model.withTermination
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A recurrence rule written in the DSL can be bounded.
 *
 * The `until` clause is stripped before the rule grammar runs, so it composes
 * with every existing rule without any of them knowing about it — which is the
 * property these tests pin, alongside the boundary being inclusive.
 */
@Tag("fast")
class RecurrenceUntilClauseTest {

    private val end = LocalDate(2026, 12, 31)

    // ─── The clause attaches to every rule shape ──────────────────────────────

    @Test
    fun `spelled-out until bounds a short form rule`() {
        val spec = RecurrenceParser.parse("+1w until 2026-12-31") as Interval
        assertEquals(1, spec.amount)
        assertEquals(DateTimeUnit.WEEK, spec.unit)
        assertEquals(RecurrenceBase.FROM_COMPLETION, spec.base)
        assertEquals(RecurrenceTermination(end), spec.termination)
    }

    @Test
    fun `concatenated until= bounds a rule in the rrule basic form`() {
        val spec = RecurrenceParser.parse("every week until=20261231") as Interval
        assertEquals(DateTimeUnit.WEEK, spec.unit)
        assertEquals(RecurrenceTermination(end), spec.termination)
    }

    @Test
    fun `until composes with a catch-up rule`() {
        val spec = RecurrenceParser.parse("!+1w until 2026-12-31") as Interval
        assertEquals(RecurrenceBase.CATCH_UP, spec.base)
        assertEquals(RecurrenceTermination(end), spec.termination)
    }

    @Test
    fun `until composes with a weekday rule`() {
        val spec = RecurrenceParser.parse("every monday until 2026-12-31")
        assertEquals(setOf(1), (spec as RecurrenceSpec.Weekly).weekdays)
        assertEquals(RecurrenceTermination(end), spec.termination)
    }

    @Test
    fun `until composes with a yearly date rule`() {
        val spec = RecurrenceParser.parse("every december 25 until 2026-12-31") as RecurrenceSpec.Yearly
        assertEquals(12, spec.month)
        assertEquals(25, spec.day)
        assertEquals(RecurrenceTermination(end), spec.termination)
    }

    @Test
    fun `until is case-insensitive`() {
        val spec = RecurrenceParser.parse("+1w UNTIL 2026-12-31")
        assertEquals(RecurrenceTermination(end), spec.termination)
    }

    // ─── Nothing changes for a rule without the clause ────────────────────────

    @Test
    fun `a rule without until stays unbounded`() {
        val spec = RecurrenceParser.parse("+1w") as Interval
        assertNull(spec.termination)
    }

    @Test
    fun `a bounded rule is byte-identical to the unbounded one plus a date`() {
        // The unbounded rule must keep exactly the JSON it had before this existed.
        assertEquals(
            RecurrenceParser.parse("+1w"),
            (RecurrenceParser.parse("+1w") as Interval)
                .withTermination(null),
        )
    }

    // ─── Malformed input is rejected, not guessed ──────────────────────────────

    @Test
    fun `an unparseable end date is rejected`() {
        assertFailsWith<IllegalArgumentException> { RecurrenceParser.parse("+1w until someday") }
    }

    @Test
    fun `an impossible date is rejected`() {
        assertFailsWith<IllegalArgumentException> { RecurrenceParser.parse("+1w until 2026-02-30") }
    }

    @Test
    fun `a month out of range is rejected`() {
        assertFailsWith<IllegalArgumentException> { RecurrenceParser.parse("+1w until 2026-13-01") }
    }

    @Test
    fun `an end date with no rule is rejected`() {
        assertFailsWith<IllegalArgumentException> { RecurrenceParser.parse("until 2026-12-31") }
    }

    // ─── The bound means what it says ──────────────────────────────────────────

    @Test
    fun `the parsed bound is inclusive`() {
        val spec = RecurrenceParser.parse("+1w until 2026-12-31")
        assertEquals(false, spec.isExhaustedBy(end), "the end date itself still recurs")
        assertEquals(true, spec.isExhaustedBy(LocalDate(2027, 1, 1)), "one past it stops")
    }
}

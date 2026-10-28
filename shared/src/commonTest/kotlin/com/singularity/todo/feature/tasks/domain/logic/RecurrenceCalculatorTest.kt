package com.singularity.todo.feature.tasks.domain.logic

import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Monthly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Weekly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Yearly
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals

@Tag("fast")
class RecurrenceCalculatorTest {

    companion object {
        private fun d(y: Int, m: Int, day: Int) = LocalDate(y, m, day)

        @JvmStatic
        fun nextOccurrenceCases(): List<Arguments> = listOf(
            // ── Interval ─────────────────────────────────────────────────────
            Arguments.of(Interval(RecurrenceBase.FROM_COMPLETION, 1, DateTimeUnit.DAY), d(2026, 1, 15), d(2026, 1, 16)),
            Arguments.of(Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.WEEK), d(2026, 1, 15), d(2026, 1, 22)),
            Arguments.of(Interval(RecurrenceBase.FROM_DUE, 2, DateTimeUnit.WEEK), d(2026, 1, 15), d(2026, 1, 29)),
            Arguments.of(Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.MONTH), d(2026, 1, 15), d(2026, 2, 15)),
            Arguments.of(Interval(RecurrenceBase.FROM_DUE, 1, DateTimeUnit.YEAR), d(2026, 1, 15), d(2027, 1, 15)),
            // ── Weekly ───────────────────────────────────────────────────────
            // Friday Jan 16 with Mon/Wed/Fri → next is Monday Jan 19 (wraps the week).
            Arguments.of(Weekly(RecurrenceBase.FROM_DUE, setOf(1, 3, 5)), d(2026, 1, 16), d(2026, 1, 19)),
            // Wednesday Jan 14 with Wed/Fri → next is Friday Jan 16.
            Arguments.of(Weekly(RecurrenceBase.FROM_DUE, setOf(3, 5)), d(2026, 1, 14), d(2026, 1, 16)),
            // Thursday Jan 15 with Tue/Thu → next is Tuesday Jan 20.
            Arguments.of(Weekly(RecurrenceBase.FROM_DUE, setOf(2, 4)), d(2026, 1, 15), d(2026, 1, 20)),
            // ── Monthly ──────────────────────────────────────────────────────
            Arguments.of(Monthly(RecurrenceBase.FROM_DUE, 15), d(2026, 1, 15), d(2026, 2, 15)),
            // Feb has 28 days in 2026, so Jan 31 + 1 month caps at Feb 28.
            Arguments.of(Monthly(RecurrenceBase.FROM_DUE, 31), d(2026, 1, 31), d(2026, 2, 28)),
            // ── Yearly ───────────────────────────────────────────────────────
            Arguments.of(Yearly(RecurrenceBase.FROM_DUE, 1, 15), d(2026, 1, 15), d(2027, 1, 15)),
        )

        @JvmStatic
        fun missedCountCases(): List<Arguments> = listOf(
            // Today at or before the anchor: nothing missed.
            Arguments.of(Interval(RecurrenceBase.CATCH_UP, 1, DateTimeUnit.DAY), d(2026, 1, 15), d(2026, 1, 15), 0),
            Arguments.of(Interval(RecurrenceBase.CATCH_UP, 1, DateTimeUnit.DAY), d(2026, 1, 16), d(2026, 1, 15), 0),
            // Jan 10 anchor, Jan 15 today: the 11th..15th are five occurrences.
            Arguments.of(Interval(RecurrenceBase.CATCH_UP, 1, DateTimeUnit.DAY), d(2026, 1, 10), d(2026, 1, 15), 5),
            // A year of missed days is capped at MAX_MISSED (10).
            Arguments.of(Interval(RecurrenceBase.CATCH_UP, 1, DateTimeUnit.DAY), d(2026, 1, 1), d(2026, 12, 31), 10),
            Arguments.of(Interval(RecurrenceBase.CATCH_UP, 1, DateTimeUnit.WEEK), d(2026, 1, 1), d(2026, 2, 1), 4),
        )
    }

    @ParameterizedTest
    @MethodSource("nextOccurrenceCases")
    fun `next occurrence follows the rule`(spec: RecurrenceSpec, anchor: LocalDate, expected: LocalDate) {
        assertEquals(expected, RecurrenceCalculator.nextOccurrence(spec, anchor))
    }

    @ParameterizedTest
    @MethodSource("missedCountCases")
    fun `missed count counts occurrences up to today`(
        spec: RecurrenceSpec,
        anchor: LocalDate,
        today: LocalDate,
        expected: Int,
    ) {
        assertEquals(expected, RecurrenceCalculator.missedCount(spec, anchor, today))
    }
}

package com.singularity.todo.feature.tasks.presentation.components

import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Monthly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Weekly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Yearly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceTermination
import io.qameta.allure.Epic
import io.qameta.allure.Feature
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

@Tag("fast")
@Epic("Tasks")
@Feature("Presentation")
class RecurrenceFormattersTest {
    private fun d(
        y: Int,
        m: Int,
        day: Int,
    ) = LocalDate(y, m, day)

    // ─── The sentence ──────────────────────────────────────────────────────────

    @Test
    fun `an interval reads as a sentence`() {
        val spec = Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY)
        assertEquals(
            "Every 3 days, from the due date",
            formatRecurrence(spec, locale(interval = "Every 3 days")),
        )
    }

    @Test
    fun `a weekly rule names its weekdays`() {
        val spec = Weekly(RecurrenceBase.FROM_DUE, setOf(1, 3, 5))
        assertEquals(
            "Weekly on Mon, Wed, Fri, from the due date",
            formatRecurrence(spec, locale(weekly = "Weekly on Mon, Wed, Fri")),
        )
    }

    @Test
    fun `a monthly rule names its day`() {
        val spec = Monthly(RecurrenceBase.FROM_DUE, 15)
        assertEquals(
            "Monthly on day 15, from the due date",
            formatRecurrence(spec, locale(monthly = "Monthly on day 15")),
        )
    }

    @Test
    fun `a yearly rule names its date`() {
        val spec = Yearly(RecurrenceBase.FROM_DUE, 12, 25)
        assertEquals(
            "Yearly on Dec 25, from the due date",
            formatRecurrence(spec, locale(yearly = "Yearly on Dec 25")),
        )
    }

    @Test
    fun `the base says what the rule is measured from`() {
        val spec = Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY)
        assertEquals(
            "Every 3 days, after completion",
            formatRecurrence(spec, locale(interval = "Every 3 days", base = "after completion")),
        )
        assertEquals(
            "Every 3 days, catch-up, filling the gaps",
            formatRecurrence(spec, locale(interval = "Every 3 days", base = "catch-up, filling the gaps")),
        )
    }

    @Test
    fun `the anchor date says when the series starts`() {
        val spec = Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY)
        assertEquals(
            "Every 3 days, from the due date, starting 12 Nov 2026",
            formatRecurrence(
                spec,
                locale(interval = "Every 3 days", starting = "starting 12 Nov 2026"),
            ),
        )
    }

    @Test
    fun `the end date says when the series stops`() {
        val spec =
            Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY)
                .copy(termination = RecurrenceTermination(d(2027, 1, 29)))
        assertEquals(
            "Every 3 days, from the due date, until 29 Jan 2027",
            formatRecurrence(
                spec,
                locale(interval = "Every 3 days", until = "until 29 Jan 2027"),
            ),
        )
    }

    @Test
    fun `an unbounded series has no end clause`() {
        val spec = Interval(RecurrenceBase.FROM_DUE, 3, DateTimeUnit.DAY)
        assertEquals(
            "Every 3 days, from the due date",
            formatRecurrence(spec, locale(interval = "Every 3 days")),
        )
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

    /**
     * The words the sentence is built from.
     *
     * A stand-in for the resource bundle: the phrases are the ones the English
     * bundle holds, so a test says what a person reads and not what the
     * formatter does with the words.
     */
    private fun locale(
        interval: String = "Every 3 days",
        weekly: String = "Weekly on Mon, Wed, Fri",
        monthly: String = "Monthly on day 15",
        yearly: String = "Yearly on Dec 25",
        base: String = "from the due date",
        starting: String? = null,
        until: String? = null,
    ) = RecurrenceLocale(
        interval = interval,
        weekly = weekly,
        monthly = monthly,
        yearly = yearly,
        base = base,
        starting = starting,
        until = until,
    )
}

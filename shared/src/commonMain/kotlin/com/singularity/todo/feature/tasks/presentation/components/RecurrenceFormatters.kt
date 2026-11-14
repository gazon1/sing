package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.runtime.Composable
import com.singularity.todo.core.ui.components.localizedShortDateWithYear
import com.singularity.todo.feature.tasks.domain.logic.RecurrenceCalculator
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Interval
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Monthly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.RecurrenceBase
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Weekly
import com.singularity.todo.feature.tasks.domain.model.RecurrenceSpec.Yearly
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import singularity_todo.shared.generated.resources.Res
import singularity_todo.shared.generated.resources.month_short_apr
import singularity_todo.shared.generated.resources.month_short_aug
import singularity_todo.shared.generated.resources.month_short_dec
import singularity_todo.shared.generated.resources.month_short_feb
import singularity_todo.shared.generated.resources.month_short_jan
import singularity_todo.shared.generated.resources.month_short_jul
import singularity_todo.shared.generated.resources.month_short_jun
import singularity_todo.shared.generated.resources.month_short_mar
import singularity_todo.shared.generated.resources.month_short_may
import singularity_todo.shared.generated.resources.month_short_nov
import singularity_todo.shared.generated.resources.month_short_oct
import singularity_todo.shared.generated.resources.month_short_sep
import singularity_todo.shared.generated.resources.recurrence_catch_up
import singularity_todo.shared.generated.resources.recurrence_every_day
import singularity_todo.shared.generated.resources.recurrence_every_days
import singularity_todo.shared.generated.resources.recurrence_every_month
import singularity_todo.shared.generated.resources.recurrence_every_months
import singularity_todo.shared.generated.resources.recurrence_every_week
import singularity_todo.shared.generated.resources.recurrence_every_weeks
import singularity_todo.shared.generated.resources.recurrence_every_year
import singularity_todo.shared.generated.resources.recurrence_every_years
import singularity_todo.shared.generated.resources.recurrence_from_completion
import singularity_todo.shared.generated.resources.recurrence_from_due
import singularity_todo.shared.generated.resources.recurrence_monthly
import singularity_todo.shared.generated.resources.recurrence_sentence
import singularity_todo.shared.generated.resources.recurrence_sentence_with_end
import singularity_todo.shared.generated.resources.recurrence_sentence_with_start
import singularity_todo.shared.generated.resources.recurrence_sentence_with_start_and_end
import singularity_todo.shared.generated.resources.recurrence_weekday_separator
import singularity_todo.shared.generated.resources.recurrence_weekday_short_fri
import singularity_todo.shared.generated.resources.recurrence_weekday_short_mon
import singularity_todo.shared.generated.resources.recurrence_weekday_short_sat
import singularity_todo.shared.generated.resources.recurrence_weekday_short_sun
import singularity_todo.shared.generated.resources.recurrence_weekday_short_thu
import singularity_todo.shared.generated.resources.recurrence_weekday_short_tue
import singularity_todo.shared.generated.resources.recurrence_weekday_short_wed
import singularity_todo.shared.generated.resources.recurrence_weekly
import singularity_todo.shared.generated.resources.recurrence_yearly

/**
 * Pure UI string formatters for [RecurrenceSpec].
 *
 * Internal — called only from UI/component layer.
 *
 */
object RecurrenceFormatters {
    /**
     * Base mode label for the picker UI.
     */
    fun baseLabel(base: RecurrenceBase): String =
        when (base) {
            RecurrenceBase.FROM_DUE -> "From due date"
            RecurrenceBase.FROM_COMPLETION -> "From completion"
            RecurrenceBase.CATCH_UP -> "Catch-up (fill gaps)"
        }

    /**
     * Preview string showing the next occurrence given the anchor date.
     */
    fun nextOccurrencePreview(
        anchor: LocalDate,
        spec: RecurrenceSpec,
    ): String {
        val next = RecurrenceCalculator.nextOccurrence(spec, anchor)
        return "Next: $next"
    }
}

/**
 * The rule, as a sentence, in the app's resource locale.
 *
 * The one place a rule and a task meet: the task detail row and the picker's
 * preview both read the sentence from here, so the same rule in the same
 * context reads the same in both places.
 *
 * @param spec The rule to read.
 * @param dueDate The task's due date — the date a rule measured from the due
 *        date starts on. Null when the task has no due date, and then such a
 *        rule has no start date to show.
 */
@Composable
fun RecurrenceSentence(
    spec: RecurrenceSpec,
    dueDate: LocalDate?,
): String = formatRecurrence(spec, recurrenceStartDate(spec.base, dueDate))

/**
 * The date the series is measured from — the date the "starting" clause names.
 *
 * The one place that decides it, because the base says what the rule hangs on:
 *
 * - [RecurrenceBase.FROM_DUE] — the task's due date.
 * - [RecurrenceBase.CATCH_UP] — the task's due date too: catch-up fills the
 *   gaps between *scheduled* occurrences, and those are measured from the due
 *   date, not from the completion.
 * - [RecurrenceBase.FROM_COMPLETION] — nothing. The series is measured from
 *   every completion, so its anchor moves with every occurrence and the series
 *   has no date it starts on. The task's completion date is the *last*
 *   completion, not the first, and it is not what the rule hangs on.
 *
 * A rule with no date shows no date: the sentence says what repeats and what
 * it is measured from, and nothing it cannot say.
 *
 * @param base What the rule hangs on.
 * @param dueDate The task's due date, or null when the task has none.
 */
internal fun recurrenceStartDate(
    base: RecurrenceBase,
    dueDate: LocalDate?,
): LocalDate? =
    when (base) {
        RecurrenceBase.FROM_DUE -> dueDate
        RecurrenceBase.CATCH_UP -> dueDate
        RecurrenceBase.FROM_COMPLETION -> null
    }

/**
 * The unit a rule's interval is counted in.
 */
internal enum class RecurrenceUnit {
    DAY,
    WEEK,
    MONTH,
    YEAR,
}

/**
 * An interval rule read as "every [count] [unit]".
 */
internal data class RecurrenceInterval(val count: Int, val unit: RecurrenceUnit)

/**
 * The interval of a rule, read as a count and a unit.
 *
 * Every date-based unit is named by what it is: seven days is a week, twelve
 * months is a year, and anything else keeps the unit it has — three days is
 * three days, three months is three months. The formatter used to fall back to
 * days for a unit it did not know, so a rule stored as "every 3 months" read
 * as "every 3 days": a wrong rule, not a rough one.
 */
internal fun intervalOf(interval: Interval): RecurrenceInterval =
    when (val unit = interval.unit) {
        is DateTimeUnit.DayBased -> {
            if (unit.days % 7 == 0) {
                RecurrenceInterval(interval.amount * (unit.days / 7), RecurrenceUnit.WEEK)
            } else {
                RecurrenceInterval(interval.amount * unit.days, RecurrenceUnit.DAY)
            }
        }

        is DateTimeUnit.MonthBased -> {
            if (unit.months % 12 == 0) {
                RecurrenceInterval(interval.amount * (unit.months / 12), RecurrenceUnit.YEAR)
            } else {
                RecurrenceInterval(interval.amount * unit.months, RecurrenceUnit.MONTH)
            }
        }
    }

/**
 * The rule, as a sentence, in the app's resource locale.
 *
 * "Every 3 days, from the due date, starting 12 Nov 2026, until 29 Jan 2025" —
 * never "Interval(base=FROM_DUE, amount=3, unit=DAY, termination=null)", which is
 * what the rule's own `toString()` says and what a person cannot read.
 *
 * The parts are put together by the language's own template, so the word order
 * and the punctuation are the resource's and not the code's.
 *
 * @param spec The rule to read.
 * @param startDate The date the series is measured from — see
 *        [recurrenceStartDate]. Null when the rule has no date to show.
 */
@Composable
internal fun formatRecurrence(
    spec: RecurrenceSpec,
    startDate: LocalDate?,
): String {
    val rule = rulePhrase(spec)
    val base = stringResource(baseResource(spec.base))
    val start = startDate?.let { localizedShortDateWithYear(it) }
    val end = spec.termination?.endDate?.let { localizedShortDateWithYear(it) }
    return when {
        start != null && end != null -> {
            stringResource(Res.string.recurrence_sentence_with_start_and_end, rule, base, start, end)
        }

        start != null -> {
            stringResource(Res.string.recurrence_sentence_with_start, rule, base, start)
        }

        end != null -> {
            stringResource(Res.string.recurrence_sentence_with_end, rule, base, end)
        }

        else -> {
            stringResource(Res.string.recurrence_sentence, rule, base)
        }
    }
}

/** What repeats. */
@Composable
private fun rulePhrase(spec: RecurrenceSpec): String =
    when (spec) {
        is Interval -> intervalPhrase(spec)
        is Weekly -> weeklyPhrase(spec)
        is Monthly -> stringResource(Res.string.recurrence_monthly, spec.dayOfMonth)
        is Yearly -> yearlyPhrase(spec)
    }

/**
 * "Every 3 days" — the count pluralized the way the locale pluralizes it.
 *
 * The count of one is a string of its own: "Every 1 day" is not what a person
 * says, "Every day" is.
 */
@Composable
private fun intervalPhrase(interval: Interval): String {
    val (count, unit) = intervalOf(interval)
    return if (count == 1) {
        stringResource(singularResource(unit))
    } else {
        pluralStringResource(pluralResource(unit), count, count)
    }
}

/** "Weekly on Mon, Wed, Fri". */
@Composable
private fun weeklyPhrase(weekly: Weekly): String {
    // The names are read first: `joinToString` takes a function it may hold on
    // to, and a resource read is only allowed where the composition can see it.
    val names = weekly.weekdays.sorted().map { stringResource(weekdayResource(it)) }
    return stringResource(
        Res.string.recurrence_weekly,
        names.joinToString(stringResource(Res.string.recurrence_weekday_separator)),
    )
}

/** "Yearly on Dec 25". */
@Composable
private fun yearlyPhrase(yearly: Yearly): String =
    stringResource(
        Res.string.recurrence_yearly,
        stringResource(monthResource(yearly.month)),
        yearly.day,
    )

/** The "every day" string for a unit. */
private fun singularResource(unit: RecurrenceUnit): StringResource =
    when (unit) {
        RecurrenceUnit.DAY -> Res.string.recurrence_every_day
        RecurrenceUnit.WEEK -> Res.string.recurrence_every_week
        RecurrenceUnit.MONTH -> Res.string.recurrence_every_month
        RecurrenceUnit.YEAR -> Res.string.recurrence_every_year
    }

private fun pluralResource(unit: RecurrenceUnit): PluralStringResource =
    when (unit) {
        RecurrenceUnit.DAY -> Res.plurals.recurrence_every_days
        RecurrenceUnit.WEEK -> Res.plurals.recurrence_every_weeks
        RecurrenceUnit.MONTH -> Res.plurals.recurrence_every_months
        RecurrenceUnit.YEAR -> Res.plurals.recurrence_every_years
    }

private fun baseResource(base: RecurrenceBase): StringResource =
    when (base) {
        RecurrenceBase.FROM_DUE -> Res.string.recurrence_from_due
        RecurrenceBase.FROM_COMPLETION -> Res.string.recurrence_from_completion
        RecurrenceBase.CATCH_UP -> Res.string.recurrence_catch_up
    }

private fun weekdayResource(isoDayOfWeek: Int): StringResource =
    when (DayOfWeek(isoDayOfWeek)) {
        DayOfWeek.MONDAY -> Res.string.recurrence_weekday_short_mon
        DayOfWeek.TUESDAY -> Res.string.recurrence_weekday_short_tue
        DayOfWeek.WEDNESDAY -> Res.string.recurrence_weekday_short_wed
        DayOfWeek.THURSDAY -> Res.string.recurrence_weekday_short_thu
        DayOfWeek.FRIDAY -> Res.string.recurrence_weekday_short_fri
        DayOfWeek.SATURDAY -> Res.string.recurrence_weekday_short_sat
        DayOfWeek.SUNDAY -> Res.string.recurrence_weekday_short_sun
    }

private fun monthResource(month: Int): StringResource =
    when (Month(month)) {
        Month.JANUARY -> Res.string.month_short_jan
        Month.FEBRUARY -> Res.string.month_short_feb
        Month.MARCH -> Res.string.month_short_mar
        Month.APRIL -> Res.string.month_short_apr
        Month.MAY -> Res.string.month_short_may
        Month.JUNE -> Res.string.month_short_jun
        Month.JULY -> Res.string.month_short_jul
        Month.AUGUST -> Res.string.month_short_aug
        Month.SEPTEMBER -> Res.string.month_short_sep
        Month.OCTOBER -> Res.string.month_short_oct
        Month.NOVEMBER -> Res.string.month_short_nov
        Month.DECEMBER -> Res.string.month_short_dec
    }

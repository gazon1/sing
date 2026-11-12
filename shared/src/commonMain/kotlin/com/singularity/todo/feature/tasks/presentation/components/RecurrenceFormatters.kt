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
import singularity_todo.shared.generated.resources.recurrence_starting
import singularity_todo.shared.generated.resources.recurrence_until
import singularity_todo.shared.generated.resources.recurrence_weekly
import singularity_todo.shared.generated.resources.recurrence_yearly
import singularity_todo.shared.generated.resources.weekday_short_fri
import singularity_todo.shared.generated.resources.weekday_short_mon
import singularity_todo.shared.generated.resources.weekday_short_sat
import singularity_todo.shared.generated.resources.weekday_short_sun
import singularity_todo.shared.generated.resources.weekday_short_thu
import singularity_todo.shared.generated.resources.weekday_short_tue
import singularity_todo.shared.generated.resources.weekday_short_wed

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
 * The localized words one recurrence sentence is built from.
 *
 * The words come from the app's string resources, so the sentence reads in the
 * resource locale. A locale is built for one rule: the plural form of "every
 * three days" belongs to the locale, and the rules for it live in the resources,
 * so the phrase is resolved while the locale is built and not while it is read.
 */
internal data class RecurrenceLocale(
    /** "Every 3 days". */
    val interval: String,
    /** "Weekly on Mon, Wed, Fri". */
    val weekly: String,
    /** "Monthly on day 15". */
    val monthly: String,
    /** "Yearly on Dec 25". */
    val yearly: String,
    /** "from the due date" — what the rule is measured from. */
    val base: String,
    /** "starting 12 Nov 2026", or null when the rule has no anchor date. */
    val starting: String?,
    /** "until 29 Jan 2025", or null when the series never stops. */
    val until: String?,
)

/**
 * The rule, as a sentence a person can read.
 *
 * "Every 3 days, from the due date, starting 12 Nov 2026, until 29 Jan 2025" —
 * never "Interval(base=FROM_DUE, amount=3, unit=DAY, termination=null)", which is
 * what the rule's own `toString()` says and what a person cannot read.
 *
 * The parts are in the order a person says them: what repeats, what it is
 * measured from, when it starts, when it stops.
 */
internal fun formatRecurrence(
    spec: RecurrenceSpec,
    locale: RecurrenceLocale,
): String {
    val core =
        when (spec) {
            is Interval -> locale.interval
            is Weekly -> locale.weekly
            is Monthly -> locale.monthly
            is Yearly -> locale.yearly
        }
    return listOfNotNull(core, locale.base, locale.starting, locale.until).joinToString(", ")
}

/**
 * The rule, as a sentence, in the app's resource locale.
 *
 * @param spec The rule to read.
 * @param anchorDate The date the series is measured from — the task's due date.
 *        Null when there is nothing to measure it from.
 */
@Composable
internal fun recurrenceSentence(
    spec: RecurrenceSpec,
    anchorDate: LocalDate? = null,
): String = formatRecurrence(spec, resourceRecurrenceLocale(spec, anchorDate))

@Composable
private fun resourceRecurrenceLocale(
    spec: RecurrenceSpec,
    anchorDate: LocalDate?,
): RecurrenceLocale =
    RecurrenceLocale(
        interval = intervalPhrase(spec),
        weekly = weeklyPhrase(spec),
        monthly = monthlyPhrase(spec),
        yearly = yearlyPhrase(spec),
        base = stringResource(baseResource(spec.base)),
        starting =
            anchorDate?.let {
                stringResource(Res.string.recurrence_starting, localizedShortDateWithYear(it))
            },
        until =
            spec.termination?.endDate?.let {
                stringResource(Res.string.recurrence_until, localizedShortDateWithYear(it))
            },
    )

/**
 * "Every 3 days" — the count pluralized the way the locale pluralizes it.
 *
 * The count of one is a string of its own: "Every 1 day" is not what a person
 * says, "Every day" is.
 */
@Composable
private fun intervalPhrase(spec: RecurrenceSpec): String {
    val interval = spec as? Interval ?: return ""
    return if (interval.amount == 1) {
        stringResource(singularResource(interval.unit))
    } else {
        pluralStringResource(pluralResource(interval.unit), interval.amount, interval.amount)
    }
}

/** "Weekly on Mon, Wed, Fri". */
@Composable
private fun weeklyPhrase(spec: RecurrenceSpec): String {
    val weekly = spec as? Weekly ?: return ""
    // The names are read first: `joinToString` takes a function it may hold on
    // to, and a resource read is only allowed where the composition can see it.
    val names = weekly.weekdays.sorted().map { stringResource(weekdayResource(it)) }
    return stringResource(Res.string.recurrence_weekly, names.joinToString(", "))
}

/** "Monthly on day 15". */
@Composable
private fun monthlyPhrase(spec: RecurrenceSpec): String {
    val monthly = spec as? Monthly ?: return ""
    return stringResource(Res.string.recurrence_monthly, monthly.dayOfMonth)
}

/** "Yearly on Dec 25". */
@Composable
private fun yearlyPhrase(spec: RecurrenceSpec): String {
    val yearly = spec as? Yearly ?: return ""
    return stringResource(
        Res.string.recurrence_yearly,
        stringResource(monthResource(yearly.month)),
        yearly.day,
    )
}

/**
 * The "every day" string for a unit.
 *
 * The picker offers no other unit, and a rule with one is not something a person
 * can be shown a sentence about, so an unknown unit falls back to the day.
 */
private fun singularResource(unit: DateTimeUnit.DateBased): StringResource =
    when (unit) {
        DateTimeUnit.DAY -> Res.string.recurrence_every_day
        DateTimeUnit.WEEK -> Res.string.recurrence_every_week
        DateTimeUnit.MONTH -> Res.string.recurrence_every_month
        DateTimeUnit.YEAR -> Res.string.recurrence_every_year
        else -> Res.string.recurrence_every_day
    }

private fun pluralResource(unit: DateTimeUnit.DateBased): PluralStringResource =
    when (unit) {
        DateTimeUnit.DAY -> Res.plurals.recurrence_every_days
        DateTimeUnit.WEEK -> Res.plurals.recurrence_every_weeks
        DateTimeUnit.MONTH -> Res.plurals.recurrence_every_months
        DateTimeUnit.YEAR -> Res.plurals.recurrence_every_years
        else -> Res.plurals.recurrence_every_days
    }

private fun baseResource(base: RecurrenceBase): StringResource =
    when (base) {
        RecurrenceBase.FROM_DUE -> Res.string.recurrence_from_due
        RecurrenceBase.FROM_COMPLETION -> Res.string.recurrence_from_completion
        RecurrenceBase.CATCH_UP -> Res.string.recurrence_catch_up
    }

private fun weekdayResource(isoDayOfWeek: Int): StringResource =
    when (DayOfWeek(isoDayOfWeek)) {
        DayOfWeek.MONDAY -> Res.string.weekday_short_mon
        DayOfWeek.TUESDAY -> Res.string.weekday_short_tue
        DayOfWeek.WEDNESDAY -> Res.string.weekday_short_wed
        DayOfWeek.THURSDAY -> Res.string.weekday_short_thu
        DayOfWeek.FRIDAY -> Res.string.weekday_short_fri
        DayOfWeek.SATURDAY -> Res.string.weekday_short_sat
        DayOfWeek.SUNDAY -> Res.string.weekday_short_sun
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

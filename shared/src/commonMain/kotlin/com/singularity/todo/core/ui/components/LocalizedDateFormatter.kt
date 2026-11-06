package com.singularity.todo.core.ui.components

import androidx.compose.runtime.Composable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
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
import singularity_todo.shared.generated.resources.weekday_short_fri
import singularity_todo.shared.generated.resources.weekday_short_mon
import singularity_todo.shared.generated.resources.weekday_short_sat
import singularity_todo.shared.generated.resources.weekday_short_sun
import singularity_todo.shared.generated.resources.weekday_short_thu
import singularity_todo.shared.generated.resources.weekday_short_tue
import singularity_todo.shared.generated.resources.weekday_short_wed

/**
 * The task editor's date labels, in the locale the rest of the screen is in.
 *
 * `RussianDateFormatter` names its weekdays and months in Russian and takes no
 * locale — there is nothing to pass it. On an English device the task editor
 * therefore printed "Пт, 30 окт 2026" while the rows around it said "Add date"
 * and "Save" (#1700). The editor asks for its labels here instead, where the
 * names come from `composeResources` and so follow the resource locale.
 *
 * The word order — weekday, day, month, year — is written into the
 * concatenation rather than into a pattern resource because the two locales the
 * app ships put those four parts in the same order. A locale that does not
 * would need a pattern here, and this comment is the place that says so.
 */
@Composable
internal fun localizedDueDateWithTime(
    date: LocalDate,
    time: LocalTime?,
): String {
    val weekday = shortWeekdayName(date.dayOfWeek)
    val day = localizedShortDate(date)
    val dateLabel = "$weekday, $day ${date.year}"
    return dateLabel + (time?.let { " $it" } ?: "")
}

/**
 * Several dates on one row: "12 Oct, 17 Oct, 3 Nov".
 *
 * Short dates, joined the way a person lists them. The separator is the same in
 * both shipped locales; it is here rather than in a resource for the same reason
 * the word order is.
 */
@Composable
internal fun localizedDateList(dates: ImmutableList<LocalDate>): String {
    val labels = dates.map { localizedShortDate(it) }
    return labels.joinToString(", ")
}

/** "30 Oct" — the day, and the month named in the resource locale. */
@Composable
internal fun localizedShortDate(date: LocalDate): String =
    "${date.day.toString().padStart(2, '0')} ${shortMonthName(date.month)}"

@Composable
private fun shortMonthName(month: Month): String =
    stringResource(
        when (month) {
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
        },
    )

@Composable
private fun shortWeekdayName(day: DayOfWeek): String =
    stringResource(
        when (day) {
            DayOfWeek.MONDAY -> Res.string.weekday_short_mon
            DayOfWeek.TUESDAY -> Res.string.weekday_short_tue
            DayOfWeek.WEDNESDAY -> Res.string.weekday_short_wed
            DayOfWeek.THURSDAY -> Res.string.weekday_short_thu
            DayOfWeek.FRIDAY -> Res.string.weekday_short_fri
            DayOfWeek.SATURDAY -> Res.string.weekday_short_sat
            DayOfWeek.SUNDAY -> Res.string.weekday_short_sun
        },
    )

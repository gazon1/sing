package com.singularity.todo.core.ui.components.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.formatRussianMonthYear
import com.singularity.todo.core.ui.components.isoLocalDate
import com.singularity.todo.core.ui.components.russianWeekdayHeaders
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableSet

/**
 * A picker that lets the user choose *several* days in one go.
 *
 * The result is one piece of work scheduled on several dates — a series. The
 * picker knows nothing about tasks; it produces a set of dates, and the caller
 * decides what they mean.
 *
 * ## The rules the picker enforces
 *
 * * **The ceiling is a refusal, not a hint.** At [maxDates] selected days the
 *   grid stops accepting new ones and says why. A silent cap would let the user
 *   believe they picked forty dates and then discover that thirty survived.
 * * **One month at a time, but the selection spans months.** Paging to the next
 *   month does not drop what was picked before.
 * * **Today is marked, not preselected.** Preselecting it would silently add a
 *   date the user never chose.
 *
 * @param initialDates The days that start out selected.
 * @param onConfirm Called with the selected days when the user confirms. The set
 *   is empty when the user cleared the selection.
 * @param onDismiss Called when the user backs out. Nothing is selected.
 * @param today The current day, so it can be marked in the grid. Passed in rather
 *   than read from the system clock: a picker that marked the wrong day would
 *   be a picker nobody could test.
 * @param maxDates The ceiling on the number of selected days. Required, and not
 *   defaulted: the number lives in the domain
 *   (`TaskSeriesPlanner.MAX_DATES`), and a second copy of it here would be a
 *   number that can drift from the one the domain enforces.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiDatePickerSheet(
    initialDates: ImmutableSet<LocalDate>,
    onConfirm: (ImmutableSet<LocalDate>) -> Unit,
    onDismiss: () -> Unit,
    today: LocalDate,
    maxDates: Int,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(initialDates) }
    var monthAnchor by remember { mutableStateOf(initialDates.minOrNull() ?: today) }
    var limitReached by remember { mutableStateOf(false) }

    BottomSheetHost(onDismiss = onDismiss) {
        SheetScaffold(
            modifier = modifier,
            actions = SheetActions(
                onClear = {
                    selected = persistentSetOf()
                    onConfirm(persistentSetOf())
                    onDismiss()
                },
                onCancel = onDismiss,
                onConfirm = {
                    onConfirm(selected)
                    onDismiss()
                },
                confirmLabel = "Готово",
                cancelLabel = "Отмена",
                clearLabel = "Очистить",
                testTagConfirm = TestTags.MultiDatePicker.OK,
                testTagCancel = TestTags.MultiDatePicker.CANCEL,
                testTagClear = TestTags.MultiDatePicker.CLEAR,
            ),
        ) {
            MonthHeader(
                monthAnchor = monthAnchor,
                onPrevious = { monthAnchor = monthAnchor.minusMonth() },
                onNext = { monthAnchor = monthAnchor.plusMonth() },
            )
            WeekdayHeaderRow()
            MonthGrid(
                monthAnchor = monthAnchor,
                today = today,
                selected = selected,
                onDayClick = { date ->
                    when {
                        date in selected -> {
                            selected = (selected - date).toImmutableSet()
                            limitReached = false
                        }

                        // The ceiling refuses the click and says so. The day stays
                        // unselected, so the grid still shows exactly what will be
                        // created.
                        selected.size >= maxDates -> limitReached = true

                        else -> {
                            selected = (selected + date).toImmutableSet()
                            limitReached = false
                        }
                    }
                },
            )
            SelectionCounter(
                selectedCount = selected.size,
                maxDates = maxDates,
                limitReached = limitReached,
            )
        }
    }
}

/** The month title and the two buttons that page between months. */
@Composable
private fun MonthHeader(
    monthAnchor: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPrevious,
            modifier = Modifier
                .testTag(TestTags.MultiDatePicker.PREV_MONTH)
                .mapTestTagsAsResourceIds(),
        ) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "Предыдущий месяц")
        }
        Text(
            text = formatRussianMonthYear(monthAnchor),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(TestTags.MultiDatePicker.MONTH_TITLE),
        )
        IconButton(
            onClick = onNext,
            modifier = Modifier
                .testTag(TestTags.MultiDatePicker.NEXT_MONTH)
                .mapTestTagsAsResourceIds(),
        ) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Следующий месяц")
        }
    }
}

/** The weekday names above the grid. */
@Composable
private fun WeekdayHeaderRow() {
    Row(modifier = Modifier.fillMaxWidth()) {
        russianWeekdayHeaders.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The six-week grid of the month, with the picked days marked. */
@Composable
private fun MonthGrid(
    monthAnchor: LocalDate,
    today: LocalDate,
    selected: ImmutableSet<LocalDate>,
    onDayClick: (LocalDate) -> Unit,
) {
    val gridDates = remember(monthAnchor) { monthGridDates(monthAnchor) }
    gridDates.chunked(7).forEach { week ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        ) {
            week.forEach { date ->
                MultiDateCell(
                    date = date,
                    isCurrentMonth = date.month == monthAnchor.month,
                    isToday = date == today,
                    isSelected = date in selected,
                    onClick = { onDayClick(date) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** How many days are picked, and the ceiling when it was reached. */
@Composable
private fun SelectionCounter(
    selectedCount: Int,
    maxDates: Int,
    limitReached: Boolean,
) {
    Text(
        text = "Выбрано: $selectedCount" +
            if (limitReached) " — максимум $maxDates" else "",
        style = MaterialTheme.typography.bodyMedium,
        color = if (limitReached) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.testTag(TestTags.MultiDatePicker.COUNTER),
    )
}

@Composable
private fun MultiDateCell(
    date: LocalDate,
    isCurrentMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .background(
                when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    isToday -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .testTag(TestTags.MultiDatePicker.day(isoLocalDate(date)))
            .mapTestTagsAsResourceIds(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = date.day.toString(),
            color = when {
                isSelected -> MaterialTheme.colorScheme.onPrimary
                !isCurrentMonth -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            fontSize = 14.sp,
            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** Steps back one month, keeping the day of the month where the month has one. */
private fun LocalDate.minusMonth(): LocalDate = shiftMonth(-1)

/** Steps forward one month, keeping the day of the month where the month has one. */
private fun LocalDate.plusMonth(): LocalDate = shiftMonth(1)

/**
 * Moves the date by whole months.
 *
 * The day is clamped rather than rolled over: stepping from 31 January forward
 * lands on 28 (or 29) February, not on 2 or 3 March. A picker that rolled over
 * would silently move the day the user was looking at.
 */
private fun LocalDate.shiftMonth(delta: Int): LocalDate {
    val total = year * 12 + (month.number - 1) + delta
    var newYear = total / 12
    var newMonthIndex = total % 12
    if (newMonthIndex < 0) {
        newMonthIndex += 12
        newYear -= 1
    }
    val newMonth = Month(newMonthIndex + 1)
    return LocalDate(newYear, newMonth, minOf(day, newMonth.daysIn(newYear)))
}

/** The number of days in [month] of [year]. */
private fun Month.daysIn(year: Int): Int = when (this) {
    Month.JANUARY, Month.MARCH, Month.MAY, Month.JULY, Month.AUGUST, Month.OCTOBER, Month.DECEMBER -> 31
    Month.APRIL, Month.JUNE, Month.SEPTEMBER, Month.NOVEMBER -> 30
    Month.FEBRUARY -> if (isLeapYear(year)) 29 else 28
}

private fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

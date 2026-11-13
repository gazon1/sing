package com.singularity.todo.core.ui.components.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.isoLocalDate
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.datetime.LocalDate

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
    disabledDates: ImmutableSet<LocalDate> = persistentSetOf(),
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(initialDates) }
    var monthAnchor by remember { mutableStateOf(initialDates.minOrNull() ?: today) }
    var limitReached by remember { mutableStateOf(false) }

    BottomSheetHost(onDismiss = onDismiss) {
        SheetScaffold(
            modifier = modifier,
            actions =
                SheetActions(
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = today in selected,
                    onClick = {
                        if (today in selected) {
                            selected = (selected - today).toImmutableSet()
                        } else if (today !in disabledDates && selected.size < maxDates) {
                            selected = (selected + today).toImmutableSet()
                            limitReached = false
                        }
                    },
                    label = { Text("Сегодня") },
                )
            }
            MonthCalendarHeader(
                monthAnchor = monthAnchor,
                onPrevious = { monthAnchor = shiftMonth(monthAnchor, -1) },
                onNext = { monthAnchor = shiftMonth(monthAnchor, 1) },
            )
            WeekdayHeaderRow()
            MonthCalendarGrid(
                monthAnchor = monthAnchor,
                today = today,
                selected = selected,
                disabledDates = disabledDates,
                dayTestTag = { date -> TestTags.MultiDatePicker.day(isoLocalDate(date)) },
                onDayClick = { date ->
                    if (date in disabledDates) {
                        // Disabled days are inert: no selection, no counter bump.
                    } else {
                        when {
                            date in selected -> {
                                selected = (selected - date).toImmutableSet()
                                limitReached = false
                            }

                            selected.size >= maxDates -> {
                                limitReached = true
                            }

                            else -> {
                                selected = (selected + date).toImmutableSet()
                                limitReached = false
                            }
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

/** How many days are picked, and the ceiling when it was reached. */
@Composable
private fun SelectionCounter(
    selectedCount: Int,
    maxDates: Int,
    limitReached: Boolean,
) {
    Text(
        text =
            "Выбрано: $selectedCount" +
                if (limitReached) " — максимум $maxDates" else "",
        style = MaterialTheme.typography.bodyMedium,
        color =
            if (limitReached) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        modifier = Modifier.testTag(TestTags.MultiDatePicker.COUNTER),
    )
}

package com.singularity.todo.core.ui.components.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerSheet(
    initialDate: LocalDate?,
    onDateSelected: (LocalDate?) -> Unit,
    onDismiss: () -> Unit,
    disabledDates: ImmutableSet<LocalDate> = persistentSetOf(),
) {
    val selectableDates =
        remember(disabledDates) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val date =
                        Instant
                            .fromEpochMilliseconds(utcTimeMillis)
                            .toLocalDateTime(TimeZone.UTC)
                            .date
                    return date !in disabledDates
                }
            }
        }
    val datePickerState =
        rememberDatePickerState(
            initialSelectedDateMillis =
                initialDate
                    ?.atStartOfDayIn(TimeZone.UTC)
                    ?.toEpochMilliseconds(),
            selectableDates = selectableDates,
        )
    val today =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
    BottomSheetHost(onDismiss = onDismiss) {
        SheetScaffold(
            actions =
                SheetActions(
                    onClear = {
                        onDateSelected(null)
                        onDismiss()
                    },
                    onCancel = onDismiss,
                    onConfirm = {
                        val date =
                            datePickerState.selectedDateMillis?.let {
                                Instant
                                    .fromEpochMilliseconds(it)
                                    .toLocalDateTime(TimeZone.UTC)
                                    .date
                            }
                        onDateSelected(date)
                        onDismiss()
                    },
                    testTagConfirm = TestTags.DatePicker.OK,
                    testTagCancel = TestTags.DatePicker.CANCEL,
                    testTagClear = TestTags.DatePicker.CLEAR,
                ),
        ) {
            Column {
                DateShortcutChips(
                    today = today,
                    onPick = { date ->
                        datePickerState.selectedDateMillis =
                            date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
                    },
                )
                // Constrain height so the VLazyColumn inside DatePicker scrolls internally
                // and the action buttons remain visible in the bottom sheet.
                Box(
                    modifier =
                        Modifier
                            .heightIn(max = 600.dp)
                            .testTag(TestTags.DatePicker.SHEET)
                            .mapTestTagsAsResourceIds(),
                ) {
                    DatePicker(state = datePickerState)
                }
            }
        }
    }
}

/**
 * The chips that jump the picker to a nearby day.
 *
 * "Today" carries a test tag of its own: the Material3 day grid offers no handle
 * a test can hold — a day cell carries only the library's date description, and
 * the grid composes the cells that fit on screen, so a flow that reads a day out
 * of that description depends on the device's language and on the screen's
 * height. The chip is app-owned, always on top of the sheet, and sets the very
 * date the grid would set.
 */
@Composable
private fun DateShortcutChips(
    today: LocalDate,
    onPick: (LocalDate) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = false,
            onClick = { onPick(today) },
            label = { Text("Сегодня") },
            modifier =
                Modifier
                    .testTag(TestTags.DatePicker.TODAY)
                    .mapTestTagsAsResourceIds(),
        )
        FilterChip(
            selected = false,
            onClick = { onPick(today.plus(DatePeriod(days = 1))) },
            label = { Text("Завтра") },
        )
    }
}

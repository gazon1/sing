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
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
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
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = false,
                        onClick = {
                            datePickerState.selectedDateMillis =
                                today.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
                        },
                        label = { Text("Сегодня") },
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            val tomorrow = today.plus(DatePeriod(days = 1))
                            datePickerState.selectedDateMillis =
                                tomorrow.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
                        },
                        label = { Text("Завтра") },
                    )
                }
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

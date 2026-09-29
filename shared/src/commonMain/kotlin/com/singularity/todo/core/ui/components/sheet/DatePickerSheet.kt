package com.singularity.todo.core.ui.components.sheet

import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import com.singularity.todo.core.ui.TestTags
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerSheet(initialDate: LocalDate?, onDateSelected: (LocalDate?) -> Unit, onDismiss: () -> Unit) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate?.atStartOfDayIn(TimeZone.UTC)
            ?.toEpochMilliseconds(),
    )
    BottomSheetHost(onDismiss = onDismiss) {
        SheetScaffold(
            actions = SheetActions(
                onClear = {
                    onDateSelected(null)
                    onDismiss()
                },
                onCancel = onDismiss,
                onConfirm = {
                    val date = datePickerState.selectedDateMillis?.let {
                        Instant.fromEpochMilliseconds(it)
                            .toLocalDateTime(TimeZone.UTC).date
                    }
                    onDateSelected(date)
                    onDismiss()
                },
                testTagConfirm = TestTags.DatePicker.OK,
                testTagCancel = TestTags.DatePicker.CANCEL,
                testTagClear = TestTags.DatePicker.CLEAR,
            ),
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

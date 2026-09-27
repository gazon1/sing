package com.singularity.todo.core.ui.components.sheet

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import kotlinx.datetime.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerSheet(initialTime: LocalTime? = null, onTimeSelected: (LocalTime?) -> Unit, onDismiss: () -> Unit) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialTime?.hour
            ?: 12,
        initialMinute = initialTime?.minute
            ?: 0,
    )

    BottomSheetHost(onDismiss = onDismiss) {
        SheetScaffold(
            actions = SheetActions(
                onClear = {
                    onTimeSelected(null)
                    onDismiss()
                },
                onCancel = onDismiss,
                onConfirm = {
                    onTimeSelected(LocalTime(timePickerState.hour, timePickerState.minute))
                    onDismiss()
                },
            ),
        ) {
            TimePicker(state = timePickerState)
        }
    }
}

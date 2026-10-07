package com.singularity.todo.feature.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.components.SettingsSwitchRow
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.settings.SettingsUiState

@Composable
fun WorkScheduleSettingsScreen(
    state: SettingsUiState.Content,
    onIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Work Day") {
            TimeSlider(
                label = "Start",
                value = state.workSchedule.dayStartMinutes,
                onValueChange = { onIntent(SettingsIntent.WorkSchedule.UpdateWorkDayStart(it)) },
                formatter = ::formatMinutes,
            )
            TimeSlider(
                label = "End",
                value = state.workSchedule.dayEndMinutes,
                onValueChange = { onIntent(SettingsIntent.WorkSchedule.UpdateWorkDayEnd(it)) },
                formatter = ::formatMinutes,
            )
        }

        SettingsSection(title = "Lunch Break") {
            TimeSlider(
                label = "Start",
                value = state.workSchedule.lunchStartMinutes,
                onValueChange = { onIntent(SettingsIntent.WorkSchedule.UpdateWorkLunchStart(it)) },
                formatter = ::formatMinutes,
            )
            TimeSlider(
                label = "End",
                value = state.workSchedule.lunchEndMinutes,
                onValueChange = { onIntent(SettingsIntent.WorkSchedule.UpdateWorkLunchEnd(it)) },
                formatter = ::formatMinutes,
            )
        }

        SettingsSection(title = "Working Days") {
            SettingsSwitchRow(
                title = "Saturday",
                testTag = TestTags.Settings.WORK_SCHEDULE_SATURDAY_SWITCH,
                checked = state.workSchedule.weekendSat,
                onCheckedChange = { onIntent(SettingsIntent.WorkSchedule.UpdateWeekendSat(it)) },
            )
            SettingsSwitchRow(
                title = "Sunday",
                testTag = TestTags.Settings.WORK_SCHEDULE_SUNDAY_SWITCH,
                checked = state.workSchedule.weekendSun,
                onCheckedChange = { onIntent(SettingsIntent.WorkSchedule.UpdateWeekendSun(it)) },
            )
        }

        SettingsSection(title = "Greeting Hours") {
            Text(
                "Determine time-of-day greeting message",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TimeSlider(
                label = "Morning ends at",
                value = state.greeting.morningEndHour,
                onValueChange = { onIntent(SettingsIntent.Greeting.UpdateMorningEnd(it)) },
                formatter = { formatHour(it) },
            )
            TimeSlider(
                label = "Afternoon ends at",
                value = state.greeting.afternoonEndHour,
                onValueChange = { onIntent(SettingsIntent.Greeting.UpdateAfternoonEnd(it)) },
                formatter = { formatHour(it) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeSlider(label: String, value: Int, onValueChange: (Int) -> Unit, formatter: (Int) -> String) {
    var showDialog by remember { mutableStateOf(false) }
    val hour = value / 60
    val minute = value % 60

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(
            value = formatter(value),
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDialog = true },
            trailingIcon = {
                Text(
                    text = formatter(value),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp),
                )
            },
            label = { Text(label) },
        )
        // Slider as fallback — step 15 minutes
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..1439f,
            steps = (1439 / 15) - 1,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (showDialog) {
        val timePickerState = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        onValueChange(timePickerState.hour * 60 + timePickerState.minute)
                        showDialog = false
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            },
            text = {
                TimePicker(state = timePickerState)
            },
        )
    }
}

private fun formatMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return "%02d:%02d".format(h, m)
}

private fun formatHour(hour: Int): String = "%d:00".format(hour)

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun WorkScheduleSettingsScreenLightPreview() = PreviewThemed(darkTheme = false, useSurface = false) {
    WorkScheduleSettingsScreen(
        state = SettingsUiState.Content(
            workSchedule = SettingsSection.WorkSchedule(
                dayStartMinutes = 540, // 09:00
                dayEndMinutes = 1080, // 18:00
                lunchStartMinutes = 720, // 12:00
                lunchEndMinutes = 780, // 13:00
                weekendSat = false,
                weekendSun = false,
            ),
            greeting = SettingsSection.Greeting(
                morningEndHour = 12,
                afternoonEndHour = 18,
            ),
        ),
        onIntent = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun WorkScheduleSettingsScreenDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    WorkScheduleSettingsScreen(
        state = SettingsUiState.Content(
            workSchedule = SettingsSection.WorkSchedule(
                dayStartMinutes = 480, // 08:00
                dayEndMinutes = 1200, // 20:00
                weekendSat = true,
                weekendSun = true,
            ),
        ),
        onIntent = {},
    )
}

package com.singularity.todo.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.components.SettingsSwitchRow
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.settings.SettingsIntent
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
                value = state.workDayStartMinutes,
                onValueChange = { onIntent(SettingsIntent.UpdateWorkDayStart(it)) },
                formatter = ::formatMinutes,
            )
            TimeSlider(
                label = "End",
                value = state.workDayEndMinutes,
                onValueChange = { onIntent(SettingsIntent.UpdateWorkDayEnd(it)) },
                formatter = ::formatMinutes,
            )
        }

        SettingsSection(title = "Lunch Break") {
            TimeSlider(
                label = "Start",
                value = state.workLunchStartMinutes,
                onValueChange = { onIntent(SettingsIntent.UpdateWorkLunchStart(it)) },
                formatter = ::formatMinutes,
            )
            TimeSlider(
                label = "End",
                value = state.workLunchEndMinutes,
                onValueChange = { onIntent(SettingsIntent.UpdateWorkLunchEnd(it)) },
                formatter = ::formatMinutes,
            )
        }

        SettingsSection(title = "Working Days") {
            SettingsSwitchRow(
                title = "Saturday",
                checked = state.workWeekendSat,
                onCheckedChange = { onIntent(SettingsIntent.UpdateWorkWeekendSat(it)) },
            )
            SettingsSwitchRow(
                title = "Sunday",
                checked = state.workWeekendSun,
                onCheckedChange = { onIntent(SettingsIntent.UpdateWorkWeekendSun(it)) },
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
                value = state.greetingMorningEnd,
                onValueChange = { onIntent(SettingsIntent.UpdateGreetingMorningEnd(it)) },
                formatter = { formatHour(it) },
            )
            TimeSlider(
                label = "Afternoon ends at",
                value = state.greetingAfternoonEnd,
                onValueChange = { onIntent(SettingsIntent.UpdateGreetingAfternoonEnd(it)) },
                formatter = { formatHour(it) },
            )
        }
    }
}

@Composable
private fun TimeSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    formatter: (Int) -> String,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(formatter(value), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..1439f,
            modifier = Modifier.fillMaxWidth(),
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
            workDayStartMinutes = 540,  // 09:00
            workDayEndMinutes = 1080,  // 18:00
            workLunchStartMinutes = 720, // 12:00
            workLunchEndMinutes = 780,   // 13:00
            workWeekendSat = false,
            workWeekendSun = false,
            greetingMorningEnd = 12,
            greetingAfternoonEnd = 18,
        ),
        onIntent = {},
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun WorkScheduleSettingsScreenDarkPreview() = PreviewThemed(darkTheme = true, useSurface = false) {
    WorkScheduleSettingsScreen(
        state = SettingsUiState.Content(
            workDayStartMinutes = 480,  // 08:00
            workDayEndMinutes = 1200,  // 20:00
            workWeekendSat = true,
            workWeekendSun = true,
        ),
        onIntent = {},
    )
}

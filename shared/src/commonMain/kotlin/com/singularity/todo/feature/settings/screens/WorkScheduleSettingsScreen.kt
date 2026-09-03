package com.singularity.todo.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.settings.SettingsIntent
import com.singularity.todo.feature.settings.SettingsUiState

private fun Int.minutesToHhMm(): String {
    val h = this / 60
    val m = this % 60
    return "%02d:%02d".format(h, m)
}

@Composable
fun WorkScheduleSettingsScreen(
    state: SettingsUiState.Content,
    onIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Work day range
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Work Day", style = MaterialTheme.typography.titleSmall)
                TimeRangeSlider(
                    label = "Start",
                    value = state.workDayStartMinutes,
                    range = 0..1439,
                    onValueChange = { onIntent(SettingsIntent.UpdateWorkDayStart(it)) }
                )
                TimeRangeSlider(
                    label = "End",
                    value = state.workDayEndMinutes,
                    range = 0..1439,
                    onValueChange = { onIntent(SettingsIntent.UpdateWorkDayEnd(it)) }
                )
            }
        }

        // Lunch break
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Lunch Break", style = MaterialTheme.typography.titleSmall)
                TimeRangeSlider(
                    label = "Start",
                    value = state.workLunchStartMinutes,
                    range = 0..1439,
                    onValueChange = { onIntent(SettingsIntent.UpdateWorkLunchStart(it)) }
                )
                TimeRangeSlider(
                    label = "End",
                    value = state.workLunchEndMinutes,
                    range = 0..1439,
                    onValueChange = { onIntent(SettingsIntent.UpdateWorkLunchEnd(it)) }
                )
            }
        }

        // Weekend toggles
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Working Days", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Saturday")
                    Switch(
                        checked = state.workWeekendSat,
                        onCheckedChange = { onIntent(SettingsIntent.UpdateWorkWeekendSat(it)) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Sunday")
                    Switch(
                        checked = state.workWeekendSun,
                        onCheckedChange = { onIntent(SettingsIntent.UpdateWorkWeekendSun(it)) }
                    )
                }
            }
        }

        // Greeting hours
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Greeting Hours", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Determine time-of-day greeting message",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                HourSlider(
                    label = "Morning ends at",
                    value = state.greetingMorningEnd,
                    onValueChange = { onIntent(SettingsIntent.UpdateGreetingMorningEnd(it)) }
                )
                HourSlider(
                    label = "Afternoon ends at",
                    value = state.greetingAfternoonEnd,
                    onValueChange = { onIntent(SettingsIntent.UpdateGreetingAfternoonEnd(it)) }
                )
            }
        }
    }
}

@Composable
private fun TimeRangeSlider(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value.minutesToHhMm(), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun HourSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("$value:00", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..23f,
            steps = 22,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

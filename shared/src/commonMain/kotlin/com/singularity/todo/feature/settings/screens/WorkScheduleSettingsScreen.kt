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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.format.formatPadded
import com.singularity.todo.core.schedule.WorkingDays
import com.singularity.todo.core.schedule.displayName
import com.singularity.todo.core.settings.SettingsIntent
import com.singularity.todo.core.settings.SettingsSection
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.SettingsSection
import com.singularity.todo.core.ui.components.SettingsSwitchRow
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.core.ui.textfields.rememberAnchoredTextFieldState
import com.singularity.todo.feature.settings.SettingsUiState
import kotlinx.datetime.DayOfWeek

@Composable
fun WorkScheduleSettingsScreen(
    state: SettingsUiState.Content,
    onIntent: (SettingsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Work Day") {
            TimeSlider(
                label = "Start",
                value = state.workSchedule.dayStartMinutes,
                onValueChange = { onIntent(SettingsIntent.WorkSchedule.UpdateWorkDayStart(it)) },
                unit = TimeSliderUnit.Minutes,
            )
            TimeSlider(
                label = "End",
                value = state.workSchedule.dayEndMinutes,
                onValueChange = { onIntent(SettingsIntent.WorkSchedule.UpdateWorkDayEnd(it)) },
                unit = TimeSliderUnit.Minutes,
            )
        }

        SettingsSection(title = "Lunch Break") {
            TimeSlider(
                label = "Start",
                value = state.workSchedule.lunchStartMinutes,
                onValueChange = { onIntent(SettingsIntent.WorkSchedule.UpdateWorkLunchStart(it)) },
                unit = TimeSliderUnit.Minutes,
            )
            TimeSlider(
                label = "End",
                value = state.workSchedule.lunchEndMinutes,
                onValueChange = { onIntent(SettingsIntent.WorkSchedule.UpdateWorkLunchEnd(it)) },
                unit = TimeSliderUnit.Minutes,
            )
        }

        SettingsSection(title = "Working Days") {
            // The whole week, not the weekend: the section says "Working Days",
            // and two switches named after Saturday and Sunday could only ever
            // say something about the weekend. The order is the ISO order, so
            // Monday is first and Sunday is last — the week as people read it.
            DayOfWeek.entries.forEach { day ->
                SettingsSwitchRow(
                    title = day.displayName(),
                    testTag = TestTags.Settings.workingDaySwitch(day),
                    checked = day in state.workSchedule.workingDays,
                    onCheckedChange = { onChecked ->
                        val current = state.workSchedule.workingDays
                        val updated = if (onChecked) current + day else current - day
                        onIntent(SettingsIntent.WorkSchedule.UpdateWorkingDays(updated))
                    },
                )
            }
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
                unit = TimeSliderUnit.Hours,
                onValueChange = { onIntent(SettingsIntent.Greeting.UpdateMorningEnd(it)) },
                sliderTag = TestTags.Settings.greetingSlider("morning"),
            )
            TimeSlider(
                label = "Afternoon ends at",
                value = state.greeting.afternoonEndHour,
                unit = TimeSliderUnit.Hours,
                onValueChange = { onIntent(SettingsIntent.Greeting.UpdateAfternoonEnd(it)) },
                sliderTag = TestTags.Settings.greetingSlider("afternoon"),
            )
        }
    }
}

/**
 * One time-of-day row: a read-only field that opens a picker, and a slider.
 *
 * [unit] is the row's single statement of what [value] counts. The slider, the
 * field and the picker all take their range and their conversions from it, so a
 * row cannot end up with an hour-valued state on a minutes-valued slider. That
 * is what the greeting rows did: the first notch turned a stored `12` into `15`,
 * and the picker wrote back `hour * 60 + minute`, so two taps produced `735`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeSlider(
    label: String,
    value: Int,
    unit: TimeSliderUnit,
    onValueChange: (Int) -> Unit,
    sliderTag: String? = null,
) {
    var showDialog by remember { mutableStateOf(false) }
    val (pickerHour, pickerMinute) = unit.pickerSeed(value)
    // Read-only display of the formatted time; the picker and the slider change it.
    val timeState = rememberAnchoredTextFieldState(unit.format(value))

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(
            state = timeState,
            readOnly = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { showDialog = true },
            trailingIcon = {
                Text(
                    text = unit.format(value),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp),
                )
            },
            label = { Text(label) },
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..unit.sliderMax,
            steps = unit.sliderSteps,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (sliderTag == null) Modifier else Modifier.testTag(sliderTag)),
        )
    }

    if (showDialog) {
        val timePickerState =
            rememberTimePickerState(
                initialHour = pickerHour,
                initialMinute = pickerMinute,
                is24Hour = true,
            )
        AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        onValueChange(unit.pickerValue(timePickerState.hour, timePickerState.minute))
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
    return "${formatPadded(h)}:${formatPadded(m)}"
}

/**
 * What a [TimeSlider]'s stored value counts.
 *
 * The greeting rows store an hour and the work-day rows store minutes, and both
 * renditions used to go through one minutes-only slider without saying which
 * they were. Naming the unit makes the slider, the field and the picker agree
 * by construction instead of by every call site remembering to pass a matching
 * formatter.
 */
internal enum class TimeSliderUnit {
    Minutes,
    Hours,
    ;

    /** The largest value the slider holds, in this unit. */
    val sliderMax: Float
        get() =
            when (this) {
                Minutes -> 1439f
                Hours -> 23f
            }

    /**
     * Discrete values between the slider's ends.
     *
     * Minutes keep the 15-minute grid the work-day rows have always had: it does
     * not divide 1439 evenly — the last interval is 14 minutes — and that is left
     * as it was. Hours are one step per hour, so one notch is one hour and the
     * thumb sits where the hour it shows says.
     */
    val sliderSteps: Int
        get() =
            when (this) {
                Minutes -> (1439 / 15) - 1
                Hours -> 22
            }

    /** The text the row's field shows for [value]. */
    fun format(value: Int): String =
        when (this) {
            Minutes -> formatMinutes(value)
            Hours -> "$value:00"
        }

    /** The picker's (hour, minute) for a stored [value]. */
    fun pickerSeed(value: Int): Pair<Int, Int> =
        when (this) {
            Minutes -> value / 60 to value % 60
            Hours -> value to 0
        }

    /**
     * The stored value a picker's (hour, minute) means.
     *
     * An hour setting has no minute component, so the picker's minutes are
     * dropped; the field then shows the hour it snapped to, which is the
     * feedback that the minutes went nowhere.
     */
    fun pickerValue(hour: Int, minute: Int): Int =
        when (this) {
            Minutes -> hour * 60 + minute
            Hours -> hour
        }
}

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun WorkScheduleSettingsScreenLightPreview() =
    PreviewThemed(darkTheme = false, useSurface = false) {
        WorkScheduleSettingsScreen(
            state =
                SettingsUiState.Content(
                    workSchedule =
                        SettingsSection.WorkSchedule(
                            dayStartMinutes = 540, // 09:00
                            dayEndMinutes = 1080, // 18:00
                            lunchStartMinutes = 720, // 12:00
                            lunchEndMinutes = 780, // 13:00
                            workingDays = WorkingDays.DEFAULT,
                        ),
                    greeting =
                        SettingsSection.Greeting(
                            morningEndHour = 12,
                            afternoonEndHour = 18,
                        ),
                ),
            onIntent = {},
        )
    }

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun WorkScheduleSettingsScreenDarkPreview() =
    PreviewThemed(darkTheme = true, useSurface = false) {
        WorkScheduleSettingsScreen(
            state =
                SettingsUiState.Content(
                    workSchedule =
                        SettingsSection.WorkSchedule(
                            dayStartMinutes = 480, // 08:00
                            dayEndMinutes = 1200, // 20:00
                            workingDays =
                                WorkingDays.DEFAULT +
                                    setOf(
                                        DayOfWeek.SATURDAY,
                                        DayOfWeek.SUNDAY,
                                    ),
                        ),
                ),
            onIntent = {},
        )
    }

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun WorkScheduleSettingsScreenShortDayPreview() =
    PreviewThemed(darkTheme = false, useSurface = false) {
        WorkScheduleSettingsScreen(
            state =
                SettingsUiState.Content(
                    workSchedule =
                        SettingsSection.WorkSchedule(
                            dayStartMinutes = 600, // 10:00
                            dayEndMinutes = 900, // 15:00
                            lunchStartMinutes = 750, // 12:30
                            lunchEndMinutes = 780, // 13:00
                            workingDays = WorkingDays.DEFAULT,
                        ),
                    greeting =
                        SettingsSection.Greeting(
                            morningEndHour = 10,
                            afternoonEndHour = 16,
                        ),
                ),
            onIntent = {},
        )
    }

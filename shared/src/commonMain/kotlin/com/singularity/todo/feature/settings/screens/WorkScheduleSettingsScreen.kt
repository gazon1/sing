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
                testTag = WorkScheduleSliderTags.DAY_START,
            )
            TimeSlider(
                label = "End",
                value = state.workSchedule.dayEndMinutes,
                onValueChange = { onIntent(SettingsIntent.WorkSchedule.UpdateWorkDayEnd(it)) },
                unit = TimeSliderUnit.Minutes,
                testTag = WorkScheduleSliderTags.DAY_END,
            )
        }

        SettingsSection(title = "Lunch Break") {
            TimeSlider(
                label = "Start",
                value = state.workSchedule.lunchStartMinutes,
                onValueChange = { onIntent(SettingsIntent.WorkSchedule.UpdateWorkLunchStart(it)) },
                unit = TimeSliderUnit.Minutes,
                testTag = WorkScheduleSliderTags.LUNCH_START,
            )
            TimeSlider(
                label = "End",
                value = state.workSchedule.lunchEndMinutes,
                onValueChange = { onIntent(SettingsIntent.WorkSchedule.UpdateWorkLunchEnd(it)) },
                unit = TimeSliderUnit.Minutes,
                testTag = WorkScheduleSliderTags.LUNCH_END,
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
                testTag = TestTags.Settings.greetingSlider("morning"),
            )
            TimeSlider(
                label = "Afternoon ends at",
                value = state.greeting.afternoonEndHour,
                unit = TimeSliderUnit.Hours,
                onValueChange = { onIntent(SettingsIntent.Greeting.UpdateAfternoonEnd(it)) },
                testTag = TestTags.Settings.greetingSlider("afternoon"),
            )
        }
    }
}

/**
 * One time-of-day row: a read-only field that opens a picker, and a slider.
 *
 * [unit] is the row's single statement of what [value] counts. The slider, the
 * field and the picker all take their range, their text and their conversions
 * from it, so a row cannot end up with an hour-valued state on a minutes-valued
 * slider. That is what the greeting rows did: the first notch turned a stored
 * `12` into `15`, and the picker wrote back `hour * 60 + minute`, so two taps
 * produced `735`.
 *
 * [value] is clamped through [TimeSliderUnit.coerce] before it reaches any of
 * them, because a stored value is not guaranteed to be sane: settings written by
 * the version above are still on disk, and a `735` handed to the picker as an
 * initial hour makes `TimePickerState` throw.
 *
 * [testTag] is required rather than optional: the tags are how the desktop tests
 * reach these sliders at all, and a row without one is a row no test can drive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeSlider(
    label: String,
    value: Int,
    unit: TimeSliderUnit,
    testTag: String,
    onValueChange: (Int) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    // What this row can legally hold. An out-of-range stored value reads as the
    // nearest legal time, which is the only meaning it can have, so the field,
    // the thumb and the picker all sit on the same value instead of the field
    // reporting "735:00" while the thumb is pinned at the end of its range.
    val shown = unit.coerce(value)
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
            value = shown.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..unit.sliderMax,
            steps = unit.sliderSteps,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(testTag),
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
 *
 * [coerce] is the same idea extended to the value itself: a row's range belongs
 * to the row's unit, so "is this a legal value for this row" is answered here
 * once instead of at each of the three places that spend the value.
 */
internal enum class TimeSliderUnit {
    Minutes,
    Hours,
    ;

    /** The largest value the row can store, in this unit. */
    val maxValue: Int
        get() =
            when (this) {
                Minutes -> 1439
                Hours -> 23
            }

    /** The largest value the slider holds, in this unit. */
    val sliderMax: Float
        get() = maxValue.toFloat()

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

    /**
     * [value] brought inside the row's range.
     *
     * A stored value is not guaranteed to be sane. The version that handed an
     * hour setting to a minutes-valued slider wrote `735` into `morningEndHour`
     * two taps after the first notch, and the settings files of the users who
     * hit it still say so; an imported snapshot carries whatever it says as well.
     * Measured in [Hours], `735` is not a time of day, and passing it on is not
     * harmless: `TimePickerState` requires its initial hour to be in `0..23` and
     * throws when it is not, so seeding the picker with the raw value would crash
     * the screen for exactly the users this row exists to repair.
     *
     * The store clamps too — `GreetingSettingsRepository` reads and writes hours
     * through an `IntPref` bounded to `0..23` — but the screen is a function of
     * a [com.singularity.todo.feature.settings.SettingsUiState.Content] that any
     * caller can build, so the row cannot delegate this to the store and still
     * be safe. Clamping reads a nonsense value as the nearest legal time, which
     * is the only meaning it can have, and the next edit writes the sane one back.
     */
    fun coerce(value: Int): Int = value.coerceIn(0, maxValue)

    /**
     * The text the row's field shows for [value].
     *
     * Both units are padded the same way, so a column of these rows reads alike:
     * an hour row says "09:00" next to a minute row that says "09:30", rather
     * than "9:00" beside "09:30" as the two used to.
     */
    fun format(value: Int): String =
        when (this) {
            Minutes -> formatMinutes(coerce(value))
            Hours -> "${formatPadded(coerce(value))}:00"
        }

    /** The picker's (hour, minute) for a stored [value], clamped into the row's range. */
    fun pickerSeed(value: Int): Pair<Int, Int> {
        val stored = coerce(value)
        return when (this) {
            Minutes -> stored / 60 to stored % 60
            Hours -> stored to 0
        }
    }

    /**
     * The stored value a picker's (hour, minute) means.
     *
     * An hour setting has no minute component, so the picker's minutes are
     * dropped. That is silent: confirming 12:45 on a row that already stores
     * `12` writes `12` again, the field keeps saying "12:00", and the dialog
     * closes with nothing to show for the minutes. The row cannot say otherwise
     * — there is no field to put them in — so it does not pretend to.
     */
    fun pickerValue(hour: Int, minute: Int): Int =
        when (this) {
            Minutes -> coerce(hour * 60 + minute)
            Hours -> hour.coerceIn(0, maxValue)
        }
}

/**
 * Test tags for the Work Day and Lunch Break sliders.
 *
 * The greeting rows are tagged through [TestTags.Settings]; these four were
 * untagged, which left the desktop suite able to drive the greeting rows only
 * and to take the minute rows on trust. They live beside the screen rather than
 * in [TestTags] because [TestTags] is the app's cross-screen vocabulary and
 * these name rows of one section.
 */
object WorkScheduleSliderTags {
    const val DAY_START: String = "settings.workSchedule.dayStart"
    const val DAY_END: String = "settings.workSchedule.dayEnd"
    const val LUNCH_START: String = "settings.workSchedule.lunchStart"
    const val LUNCH_END: String = "settings.workSchedule.lunchEnd"
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

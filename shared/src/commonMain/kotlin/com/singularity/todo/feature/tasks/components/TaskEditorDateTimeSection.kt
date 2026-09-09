package com.singularity.todo.feature.tasks.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Date + time section with quick preset chips: Today, Tomorrow, Next week.
 * Tapping a chip sets the value; tapping the row opens the full picker sheet.
 *
 * Note: date presets use the current system zone. The preset row is hidden when
 * [dueDate] is already set — the clear action is shown instead.
 */
@Composable
fun TaskEditorDateTimeSection(
    dueDate: LocalDate?,
    dueTime: LocalTime?,
    today: LocalDate,
    onDateClick: () -> Unit,
    onTimeClick: () -> Unit,
    onDatePreset: (LocalDate) -> Unit,
    onClearDate: () -> Unit,
    onClearTime: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Date row
    TaskEditorAttributeRow(
        icon = Icons.Filled.CalendarToday,
        label = "Set date",
        value = dueDate?.let { formatDateChip(it, today) },
        onClick = onDateClick,
        onClear = if (dueDate != null) onClearDate else null,
        modifier = Modifier.testTag(TestTags.TASK_EDITOR_DUE_DATE),
    )

    // Quick presets row (only shown when no date is set)
    if (dueDate == null) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val tomorrow = today.plusDays(1)
            val nextWeek = today.plusDays(7)

            PresetChip(label = "Today", onClick = { onDatePreset(today) })
            PresetChip(label = "Tomorrow", onClick = { onDatePreset(tomorrow) })
            PresetChip(label = "Next week", onClick = { onDatePreset(nextWeek) })
        }
    }

    // Time row
    TaskEditorAttributeRow(
        icon = Icons.Filled.AccessTime,
        label = "Set time",
        value = dueTime?.let { formatTimeChip(it) },
        onClick = onTimeClick,
        onClear = if (dueTime != null) onClearTime else null,
        modifier = Modifier.testTag(TestTags.TASK_EDITOR_DUE_TIME),
    )
}

@Composable
private fun PresetChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        modifier = modifier,
    )
}

private fun formatDateChip(date: LocalDate, today: LocalDate): String {
    val tomorrow = today.plusDays(1)
    return when (date) {
        today -> "Today"
        tomorrow -> "Tomorrow"
        else -> "${date.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)} ${date.day}"
    }
}

private fun formatTimeChip(time: LocalTime): String {
    val hour = if (time.hour == 0) 12 else if (time.hour > 12) time.hour - 12 else time.hour
    val ampm = if (time.hour < 12) "AM" else "PM"
    return "%d:%02d %s".format(hour, time.minute, ampm)
}

private fun LocalDate.plusDays(days: Int): LocalDate {
    return LocalDate.fromEpochDays(this.toEpochDays() + days)
}

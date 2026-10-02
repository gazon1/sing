package com.singularity.todo.feature.timetracking.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.sheet.BottomSheetHost
import com.singularity.todo.core.ui.components.sheet.DatePickerSheet
import com.singularity.todo.core.ui.components.sheet.SheetActions
import com.singularity.todo.core.ui.components.sheet.SheetScaffold
import com.singularity.todo.core.ui.components.sheet.TimePickerSheet
import com.singularity.todo.feature.timetracking.domain.TimeEntryKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Sheet for creating a manual time entry.
 *
 * @param taskStartedAtMs Epoch milliseconds of the task's creation — used as a default
 *   start time when the picker is opened without prior values.
 * @param onSave Called with (startedAtMs, endedAtMs, kind, note) when the user confirms.
 * @param onDismiss Called when the sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod") // Complex form with validation, time pickers, and kind selection
fun TimeEntryEditorSheet(
    taskStartedAtMs: Long?,
    onSave: (startedAtMs: Long, endedAtMs: Long, kind: TimeEntryKind, note: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Default: start = task creation time, end = now
    val defaultStart = taskStartedAtMs?.let {
        kotlinx.datetime.Instant.fromEpochMilliseconds(it)
            .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
    }
    val defaultEnd = kotlin.time.Clock.System.now()
        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())

    var startedDate by mutableStateOf(defaultStart?.date)
    var startedTime by mutableStateOf(defaultStart?.time ?: LocalTime(9, 0))
    var endedDate by mutableStateOf(defaultEnd.date)
    var endedTime by mutableStateOf(defaultEnd.time)
    var kind by mutableStateOf(TimeEntryKind.Work)
    var note by mutableStateOf("")

    // Picker state: null = no picker, "startDate" | "startTime" | "endDate" | "endTime"
    var activePicker by mutableStateOf<String?>(null)

    // Date/Time pickers
    if (activePicker == "startDate") {
        DatePickerSheet(
            initialDate = startedDate,
            onDateSelected = { date ->
                startedDate = date
                activePicker = "startTime"
            },
            onDismiss = { activePicker = null },
        )
    } else if (activePicker == "startTime") {
        TimePickerSheet(
            initialTime = startedTime,
            onTimeSelected = { time ->
                if (time != null) startedTime = time
                activePicker = null
            },
            onDismiss = { activePicker = null },
        )
    } else if (activePicker == "endDate") {
        DatePickerSheet(
            initialDate = endedDate,
            onDateSelected = { date ->
                if (date != null) endedDate = date
                activePicker = "endTime"
            },
            onDismiss = { activePicker = null },
        )
    } else if (activePicker == "endTime") {
        TimePickerSheet(
            initialTime = endedTime,
            onTimeSelected = { time ->
                if (time != null) endedTime = time
                activePicker = null
            },
            onDismiss = { activePicker = null },
        )
    } else {
        BottomSheetHost(onDismiss = onDismiss) {
            SheetScaffold(
                actions = SheetActions(
                    onCancel = onDismiss,
                    onConfirm = {
                        val startDt = combine(startedDate, startedTime)
                        val endDt = combine(endedDate, endedTime)
                        if (startDt != null && endDt != null) {
                            val startedAtMs = startDt.toInstant(
                                kotlinx.datetime.TimeZone.currentSystemDefault(),
                            ).toEpochMilliseconds()
                            val endedAtMs = endDt.toInstant(
                                kotlinx.datetime.TimeZone.currentSystemDefault(),
                            ).toEpochMilliseconds()
                            onSave(startedAtMs, endedAtMs, kind, note.ifBlank { null })
                        }
                    },
                    onClear = onDismiss,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Start row
                    Text(
                        text = "Start",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateRow(
                            date = startedDate,
                            label = "Date",
                            modifier = Modifier.weight(1f),
                            onClick = { activePicker = "startDate" },
                        )
                        TimeRow(
                            time = startedTime,
                            label = "Time",
                            modifier = Modifier.weight(1f),
                            onClick = { activePicker = "startTime" },
                        )
                    }

                    // End row
                    Text(
                        text = "End",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateRow(
                            date = endedDate,
                            label = "Date",
                            modifier = Modifier.weight(1f),
                            onClick = { activePicker = "endDate" },
                        )
                        TimeRow(
                            time = endedTime,
                            label = "Time",
                            modifier = Modifier.weight(1f),
                            onClick = { activePicker = "endTime" },
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Kind selection
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeEntryKind.entries.forEach { k ->
                            FilterChip(
                                selected = kind == k,
                                onClick = { kind = k },
                                label = { Text(k.name) },
                            )
                        }
                    }

                    // Note
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Note (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }
        }
    }
}

private fun combine(date: LocalDate?, time: LocalTime?): LocalDateTime? {
    if (date == null || time == null) return null
    return LocalDateTime(date, time)
}

@Composable
private fun DateRow(date: LocalDate?, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.CalendarToday,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = date?.toString() ?: "Select",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (date != null) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun TimeRow(time: LocalTime?, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.AccessTime,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = time?.toString()?.take(5) ?: "Select",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (time != null) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

package com.singularity.todo.feature.timetracking.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.formatDuration
import com.singularity.todo.core.ui.formatElapsed
import com.singularity.todo.feature.tasks.presentation.theme.TaskColors
import com.singularity.todo.feature.timetracking.domain.TimeEntry
import com.singularity.todo.feature.timetracking.domain.model.TaskTimeSlotState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Time tracking section shown in the task detail's extraSections.
 * Displays running timer, total tracked time, recent entries, and an add button.
 *
 * @param state Current time tracking state for this task.
 * @param onStart Called when the user taps Start.
 * @param onStop Called when the user taps Stop.
 * @param onAddManual Called when the user taps "Add entry".
 */
@Composable
@Suppress("LongMethod") // Task time display with summary, timer, and entry list
fun TimeTrackingSection(
    state: TaskTimeSlotState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAddManual: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TaskColors.Surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header row: icon + title + action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Timer,
                    contentDescription = null,
                    tint = TaskColors.AccentBlue,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Time",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                when (state) {
                    is TaskTimeSlotState.Running -> {
                        FilterChip(
                            selected = true,
                            onClick = onStop,
                            label = { Text("Stop") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Stop,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }

                    else -> {
                        FilterChip(
                            selected = false,
                            onClick = onStart,
                            label = { Text("Start") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
            }

            // Running timer display
            if (state is TaskTimeSlotState.Running) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatElapsed(state.elapsedMs),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TaskColors.AccentBlue,
                    )
                }
            }

            // Summary and entries
            when (state) {
                is TaskTimeSlotState.Loaded -> {
                    if (state.entries.isNotEmpty()) {
                        Text(
                            text = "Total: ${formatDuration(state.totalWorkMs)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TaskColors.TextSecondary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        state.entries.take(3).forEach { entry ->
                            TimeEntryRow(entry = entry)
                        }
                        if (state.entries.size > 3) {
                            Text(
                                text = "+${state.entries.size - 3} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = TaskColors.TextSecondary,
                            )
                        }
                    }
                }

                is TaskTimeSlotState.Idle -> {
                    Text(
                        text = "No time tracked yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TaskColors.TextSecondary,
                    )
                }

                is TaskTimeSlotState.Running -> {
                    // Already showing the timer above
                }

                TaskTimeSlotState.Loading -> {
                    Text(
                        text = "Loading...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TaskColors.TextSecondary,
                    )
                }
            }

            // Add manual entry button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAddManual)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = TaskColors.AccentBlue,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Add manual entry",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TaskColors.AccentBlue,
                )
            }
        }
    }
}

@Composable
private fun TimeEntryRow(entry: TimeEntry, modifier: Modifier = Modifier) {
    val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    val startStr = dateFormat.format(Date(entry.startedAt.toEpochMilliseconds()))
    val durationStr = entry.durationMs?.let { formatDuration(it) } ?: "in progress"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = startStr,
            style = MaterialTheme.typography.bodySmall,
            color = TaskColors.TextSecondary,
        )
        Text(
            text = durationStr,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}


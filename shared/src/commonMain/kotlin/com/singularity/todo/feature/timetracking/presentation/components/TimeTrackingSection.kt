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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.formatDuration
import com.singularity.todo.core.ui.formatElapsed
import com.singularity.todo.core.ui.formatMonthDayTime
import com.singularity.todo.feature.timetracking.domain.TimeEntry
import com.singularity.todo.feature.timetracking.domain.model.TaskTimeSlotState

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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                    tint = MaterialTheme.colorScheme.primary,
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
                            modifier = Modifier.testTag(TestTags.TimeTracking.STOP),
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

                    is TaskTimeSlotState.Error -> {
                        // The refusal is *shown*, not swallowed. Without this the
                        // state exists, the write reports it, and the chip still
                        // reads Start — so the user clicks and the only evidence
                        // is a log nobody reads. Rendering it also makes the
                        // failure assertable, which is what unblocks a desktop
                        // carrier for `TASK-TIME-01`: under the anonymous harness
                        // `startEntry` cannot succeed, and "it was refused
                        // because there is no signed-in user" is a true and
                        // useful thing to assert.
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(TestTags.TimeTracking.ERROR),
                        )
                    }

                    else -> {
                        FilterChip(
                            selected = false,
                            onClick = onStart,
                            modifier = Modifier.testTag(TestTags.TimeTracking.START),
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
                        color = MaterialTheme.colorScheme.primary,
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        state.entries.take(3).forEach { entry ->
                            TimeEntryRow(entry = entry)
                        }
                        if (state.entries.size > 3) {
                            Text(
                                text = "+${state.entries.size - 3} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                is TaskTimeSlotState.Idle -> {
                    Text(
                        text = "No time tracked yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is TaskTimeSlotState.Running -> {
                    // Already showing the timer above
                }

                // The refusal is already rendered where the chip was, above. A
                // second copy here would print the same sentence twice on one
                // screen, so the branch exists to be exhaustive and says so.
                is TaskTimeSlotState.Error -> {
                    // Shown above, in place of the chip.
                }

                TaskTimeSlotState.Loading -> {
                    Text(
                        text = "Loading...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Add manual entry",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TimeEntryRow(entry: TimeEntry, modifier: Modifier = Modifier) {
    val startStr = formatMonthDayTime(entry.startedAt)
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = durationStr,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

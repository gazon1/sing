package com.singularity.todo.feature.tasks.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.projects.Project
import com.singularity.todo.feature.projects.ProjectId
import com.singularity.todo.feature.tasks.TaskDetailActions
import com.singularity.todo.feature.tasks.TaskPriority

/**
 * A [FlowRow] of contextual chips for due-date, time, priority, and project —
 * the "metadata" bar directly below the [TaskHeroSection].
 *
 * All colours and icons are pre-computed by the caller so this composable
 * stays completely stateless and has no dependency on internal formatters.
 *
 * @param dueDateText      Formatted date string, or `null` when no date is set.
 * @param dueDateBg        Background colour for the due-date chip.
 * @param dueDateFg        Foreground (text + icon) colour for the due-date chip.
 * @param priority         Current [TaskPriority].
 * @param priorityIconColor Colour for the priority flag icon (or neutral if None).
 * @param project          Current [Project], or `null` when unassigned.
 * @param actions          Packed [TaskDetailActions] callback handler.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskMetaChipsRow(
    dueDateText: String?,
    dueDateBg: Color,
    dueDateFg: Color,
    priority: TaskPriority,
    priorityIconColor: Color,
    project: Project?,
    actions: TaskDetailActions,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── Date chip ──────────────────────────────────────────────────────────
        if (dueDateText != null) {
            FilterChip(
                selected = true,
                onClick = actions::onPickDate,
                label = { Text(dueDateText, style = MaterialTheme.typography.labelMedium) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = dueDateBg,
                    labelColor = dueDateFg,
                    iconColor = dueDateFg,
                ),
            )
        } else {
            SuggestionChip(
                onClick = actions::onPickDate,
                label = { Text("Set date", style = MaterialTheme.typography.labelMedium) },
                icon = {
                    Icon(
                        Icons.Filled.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }

        // ── Priority chip ──────────────────────────────────────────────────────
        FilterChip(
            selected = true,
            onClick = actions::onPickPriority,
            label = { Text(priority.name, style = MaterialTheme.typography.labelMedium) },
            leadingIcon = {
                Icon(
                    Icons.Filled.Flag,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = priorityIconColor,
                )
            },
        )

        // ── Project chip ───────────────────────────────────────────────────────
        if (project != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = true,
                    onClick = actions::onPickProject,
                    label = {
                        Text(project.name, style = MaterialTheme.typography.labelMedium)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
                IconButton(
                    onClick = { actions.onNavigateToProject(project.id) },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = "Open project",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        } else {
            FilterChip(
                selected = false,
                onClick = actions::onPickProject,
                label = {
                    Text("Project", style = MaterialTheme.typography.labelMedium)
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.state.TaskEditorSheet
import com.singularity.todo.feature.tasks.presentation.theme.TaskColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing

/**
 * Scrollable body of [TaskEditorContent].
 * Contains all attribute rows; visibility of each row is driven by whether
 * the corresponding callback bundle in [TaskEditorCallbacks] is null.
 *
 * @param onShowSheet called when a row is tapped to open a picker sheet.
 */
@Composable
fun TaskEditorBody(
    model: TaskEditorModel,
    callbacks: TaskEditorCallbacks,
    onShowSheet: (TaskEditorSheet) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TaskSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(TaskSpacing.md),
    ) {
        // ── Priority ───────────────────────────────────────────────────────────
        callbacks.priority?.let { pc ->
            AttributeRow(
                icon = Icons.Filled.Flag,
                label = priorityLabel(model.priority),
                iconTint = priorityColor(model.priority),
                showClear = model.priority != TaskPriority.None && pc.onClear != null,
                onClear = { pc.onClear?.invoke() },
                onClick = { onShowSheet(TaskEditorSheet.Priority) },
            )
        }

        // ── Due date ──────────────────────────────────────────────────────────
        callbacks.dueDate?.let { dc ->
            DateTimeRow(
                icon = Icons.Outlined.CalendarToday,
                label = dateTimeLabel(model.dueDate, model.dueTime),
                iconTint = if (model.dueDate != null) TaskColors.AccentBlue else TaskColors.TextSecondary,
                showClear = model.dueDate != null && dc.onClear != null,
                onClear = { dc.onClear?.invoke() },
                onClick = { onShowSheet(TaskEditorSheet.Date) },
            )
        }

        // ── Start date ────────────────────────────────────────────────────────
        callbacks.startDate?.let { sc ->
            DateTimeRow(
                icon = Icons.Outlined.Schedule,
                label = dateTimeLabel(model.startDate, model.startTime),
                iconTint = if (model.startDate != null) TaskColors.AccentBlue else TaskColors.TextSecondary,
                showClear = model.startDate != null && sc.onClear != null,
                onClear = { sc.onClear?.invoke() },
                onClick = { onShowSheet(TaskEditorSheet.StartDate) },
            )
        }

        // ── Project ────────────────────────────────────────────────────────────
        callbacks.project?.let { pc ->
            AttributeRow(
                icon = Icons.Filled.Folder,
                label = "Project",
                iconTint = if (model.project != null) TaskColors.AccentBlue else TaskColors.TextSecondary,
                showClear = model.project != null && pc.onClear != null,
                onClear = { pc.onClear?.invoke() },
                onClick = { onShowSheet(TaskEditorSheet.Project) },
            )
        }

        // ── Tags ──────────────────────────────────────────────────────────────
        callbacks.tags?.let { tc ->
            AttributeRow(
                icon = Icons.AutoMirrored.Filled.Label,
                label = "Tags",
                iconTint = if (model.tags.isNotEmpty()) TaskColors.AccentBlue else TaskColors.TextSecondary,
                showClear = model.tags.isNotEmpty() && tc.onClear != null,
                onClear = { tc.onClear?.invoke() },
                onClick = { onShowSheet(TaskEditorSheet.Tags) },
            )
        }

        // ── Recurrence ────────────────────────────────────────────────────────
        callbacks.recurrence?.let { rc ->
            val label = model.recurrence?.let { r -> r::class.simpleName } ?: "Repeat"
            AttributeRow(
                icon = Icons.Filled.Repeat,
                label = label,
                iconTint = if (model.recurrence != null) TaskColors.AccentBlue else TaskColors.TextSecondary,
                showClear = model.recurrence != null && rc.onClear != null,
                onClear = { rc.onClear?.invoke() },
                onClick = { onShowSheet(TaskEditorSheet.Recurrence) },
            )
        }

        // ── Pin ──────────────────────────────────────────────────────────────
        callbacks.pin?.let { pnc ->
            ToggleRow(
                icon = Icons.Filled.PushPin,
                label = if (model.isPinned) "Pinned" else "Pin task",
                iconTint = if (model.isPinned) TaskColors.AccentBlue else TaskColors.TextSecondary,
                onToggle = pnc.onToggle,
            )
        }

        // ── Checklist ─────────────────────────────────────────────────────────
        callbacks.checklist?.let { cc ->
            val incompleteCount = model.checklist.count { !it.isCompleted }
            val label = when {
                model.checklist.isEmpty() -> "Add checklist"
                incompleteCount == 0 -> "${model.checklist.size} checklist items"
                else -> "$incompleteCount of ${model.checklist.size} done"
            }
            AttributeRow(
                icon = Icons.Filled.CheckCircle,
                label = label,
                iconTint = if (model.checklist.isNotEmpty()) TaskColors.AccentBlue else TaskColors.TextSecondary,
                showClear = false,
                onClear = {},
                onClick = { onShowSheet(TaskEditorSheet.Checklist) },
            )
        }

        // ── Dependencies ───────────────────────────────────────────────────────
        callbacks.dependencies?.let { dc ->
            if (model.dependsOn.isNotEmpty() && model.availableTasks.isNotEmpty()) {
                val depTitles = model.dependsOn.mapNotNull { depId ->
                    model.availableTasks.find { it.id == depId }?.title?.ifBlank { null }
                }
                val displayLabel = if (depTitles.isEmpty()) {
                    "${model.dependsOn.size} dependency${if (model.dependsOn.size > 1) "s" else ""}"
                } else {
                    depTitles.joinToString(", ")
                }
                AttributeRow(
                    icon = Icons.Filled.Block,
                    label = displayLabel,
                    iconTint = TaskColors.AccentBlue,
                    showClear = dc.onClear != null,
                    onClear = { dc.onClear?.invoke() },
                    onClick = { onShowSheet(TaskEditorSheet.Dependencies) },
                )
            }
        }

        // ── Attachments ───────────────────────────────────────────────────────
        callbacks.attachments?.let { ac ->
            val label = if (model.attachments.isEmpty()) {
                "Add attachment"
            } else {
                "${model.attachments.size} attachment${if (model.attachments.size > 1) "s" else ""}"
            }
            AttributeRow(
                icon = Icons.Filled.Folder,
                label = label,
                iconTint = if (model.attachments.isNotEmpty()) TaskColors.AccentBlue else TaskColors.TextSecondary,
                showClear = false,
                onClear = {},
                onClick = { onShowSheet(TaskEditorSheet.Attachments) },
            )
        }

        Spacer(modifier = Modifier.height(TaskSpacing.xl))
    }
}

// ─── Row composables ───────────────────────────────────────────────────────────

@Composable
private fun AttributeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    iconTint: Color,
    showClear: Boolean,
    onClear: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = TaskSpacing.cardPaddingHorizontal,
                vertical = TaskSpacing.cardPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(TaskSpacing.iconSize),
        )
        Spacer(Modifier.width(TaskSpacing.lg))
        Text(
            text = label,
            color = TaskColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (showClear) {
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Clear",
                    tint = TaskColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

@Composable
private fun DateTimeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    iconTint: Color,
    showClear: Boolean,
    onClear: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = TaskSpacing.cardPaddingHorizontal,
                vertical = TaskSpacing.cardPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(TaskSpacing.iconSize),
        )
        Spacer(Modifier.width(TaskSpacing.lg))
        Text(
            text = label,
            color = if (label.startsWith("Add")) TaskColors.TextSecondary else TaskColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = if (label.startsWith("Add")) FontWeight.Normal else FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (showClear) {
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Clear",
                    tint = TaskColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

@Composable
private fun ToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    iconTint: Color,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(
                horizontal = TaskSpacing.cardPaddingHorizontal,
                vertical = TaskSpacing.cardPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(TaskSpacing.iconSize),
        )
        Spacer(Modifier.width(TaskSpacing.lg))
        Text(
            text = label,
            color = TaskColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(48.dp))
    }
}

// ─── Label helpers ─────────────────────────────────────────────────────────────

private fun priorityLabel(priority: TaskPriority): String = when (priority) {
    TaskPriority.None -> "No priority"
    TaskPriority.Low -> "Low priority"
    TaskPriority.Medium -> "Medium priority"
    TaskPriority.High -> "High priority"
    TaskPriority.Urgent -> "Urgent"
}

private fun priorityColor(priority: TaskPriority): Color = when (priority) {
    TaskPriority.None -> TaskColors.TextSecondary
    TaskPriority.Low -> TaskColors.PriorityLow
    TaskPriority.Medium -> TaskColors.PriorityMedium
    TaskPriority.High -> TaskColors.PriorityHigh
    TaskPriority.Urgent -> TaskColors.PriorityUrgent
}

private fun dateTimeLabel(date: kotlinx.datetime.LocalDate?, time: kotlinx.datetime.LocalTime?): String {
    if (date == null) return "Add date"
    val dateStr = date.toString()
    return if (time != null) "$dateStr ${time.toString().take(5)}" else dateStr
}

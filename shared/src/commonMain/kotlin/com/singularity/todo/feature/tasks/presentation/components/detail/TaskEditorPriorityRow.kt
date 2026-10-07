package com.singularity.todo.feature.tasks.presentation.components.detail
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import androidx.compose.material3.MaterialTheme

/**
 * Renders the priority attribute row in the task editor.
 * Tapping the row opens the [com.singularity.todo.feature.tasks.presentation.state.TaskEditorSheet.Priority] sheet.
 *
 * @param priority Current priority value.
 * @param onPriorityClick Called when the row is tapped to open the priority picker sheet.
 *   Pass `null` when [onPriorityClear] is also `null` (Create mode — sheet opened by default).
 * @param onPriorityClear Called when the X button is tapped. Pass `null` in Create mode
 *   (no X button is shown).
 */
@Composable
fun TaskEditorPriorityRow(
    priority: TaskPriority,
    onPriorityClick: (() -> Unit)?,
    onPriorityClear: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.TASK_EDITOR_PRIORITY_ROW)
            // No clickable() at all when the callback is null. `.clickable(onClick = {})
            // would render the same ripple and the same pointer cursor over a row that
            // does nothing — an affordance that promises an interaction and delivers
            // none. The pattern is TaskChip's, not a new one.
            .then(
                if (onPriorityClick != null) {
                    Modifier.clickable { onPriorityClick() }
                } else {
                    Modifier
                },
            )
            .padding(
                horizontal = TaskSpacing.cardPaddingHorizontal,
                vertical = TaskSpacing.cardPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val hasPriority = priority != TaskPriority.None
        val activeTint =
            if (hasPriority) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        val idleTint =
            if (hasPriority) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(
            imageVector = Icons.Filled.Flag,
            contentDescription = null,
            tint = if (hasPriority) activeTint else idleTint,
            modifier = Modifier.size(TaskSpacing.iconSize),
        )
        Spacer(Modifier.width(TaskSpacing.lg))
        Text(
            text = priority.label,
            color = if (hasPriority) idleTint else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 16.sp,
            fontWeight = if (priority != TaskPriority.None) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (priority != TaskPriority.None && onPriorityClear != null) {
            IconButton(onClick = onPriorityClear) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Сбросить приоритет",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

private val TaskPriority.label: String
    get() = when (this) {
        TaskPriority.None -> "No priority"
        TaskPriority.Low -> "Low priority"
        TaskPriority.Medium -> "Medium priority"
        TaskPriority.High -> "High priority"
        TaskPriority.Urgent -> "Urgent"
    }

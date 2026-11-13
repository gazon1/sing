package com.singularity.todo.feature.tasks.presentation.components.detail
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import com.singularity.todo.feature.tasks.presentation.theme.priorityIcon
import com.singularity.todo.feature.tasks.presentation.theme.priorityIconTint
import com.singularity.todo.feature.tasks.presentation.theme.priorityLabel
import org.jetbrains.compose.resources.stringResource
import singularity_todo.shared.generated.resources.Res
import singularity_todo.shared.generated.resources.reset_priority

/**
 * Renders the priority attribute row in the task editor.
 * Tapping the row opens the [com.singularity.todo.feature.tasks.domain.state.TaskEditorSheet.Priority] sheet.
 *
 * The row reads the level from the same glyph and the same tint the priority sheet
 * uses, so the two are one language. The level is carried by the shape and the
 * colour carries only one thing: whether the level is an exception.
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
    val isSet = priority != TaskPriority.None
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = TaskSpacing.rowMinHeight)
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
                ).padding(
                    horizontal = TaskSpacing.cardPaddingHorizontal,
                    vertical = TaskSpacing.cardPaddingVertical,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = priorityIcon(priority),
            contentDescription = null,
            tint = priorityIconTint(priority),
            modifier = Modifier.size(TaskSpacing.iconSize),
        )
        Spacer(Modifier.width(TaskSpacing.lg))
        Text(
            text = priorityLabel(priority),
            color = if (isSet) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isSet) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (isSet && onPriorityClear != null) {
            IconButton(onClick = onPriorityClear) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(Res.string.reset_priority),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(Modifier.width(TaskSpacing.clearButtonSlot))
        }
    }
}

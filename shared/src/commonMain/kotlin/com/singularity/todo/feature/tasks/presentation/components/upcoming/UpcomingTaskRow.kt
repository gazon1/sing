package com.singularity.todo.feature.tasks.presentation.components.upcoming

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.feature.tasks.presentation.components.list.TaskCheckbox
import com.singularity.todo.feature.tasks.presentation.state.UpcomingTaskUi
import com.singularity.todo.feature.tasks.presentation.theme.TaskListColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing
import com.singularity.todo.feature.tasks.presentation.theme.UpcomingTokens

/**
 * A single task row in the Upcoming screen.
 *
 * Layout (left to right):
 * [TaskCheckbox] | [14dp gap] | [title + meta row] | [UpcomingBadges]
 *
 * Title uses [UpcomingTaskUi.isOverdue] to colour the checkbox border red.
 * [UpcomingTaskUi.isRecurring] shows a repeat icon in the meta row.
 *
 * Uses [TaskCheckbox] from the shared list components (the same production-
 * component with spring-pop animation used everywhere in the app).
 */
@Composable
fun UpcomingTaskRow(
    task: UpcomingTaskUi,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TaskCheckbox(
            isChecked = task.isCompleted,
            onCheckedChange = { onToggle() },
            accentColor = if (task.isOverdue) UpcomingTokens.AccentRed else TaskListColors.Accent,
        )

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                color = if (task.isCompleted) TaskListColors.TextTertiary else TaskListColors.TextPrimary,
                fontSize = 15.sp,
                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            if (task.recurringLabel != null || task.projectName != null) {
                Spacer(Modifier.height(4.dp))
                UpcomingMetaRow(
                    recurringLabel = task.recurringLabel,
                    projectName = task.projectName,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        UpcomingBadges(task.badges)
    }

    HorizontalDivider(
        thickness = TaskListSizes.DividerThickness,
        color = TaskListColors.Divider,
        modifier = Modifier.padding(start = 54.dp),
    )
}

@Composable
private fun UpcomingMetaRow(
    recurringLabel: String?,
    projectName: String?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (recurringLabel != null) {
            Icon(
                imageVector = Icons.Filled.Repeat,
                contentDescription = "Recurring",
                tint = TaskListColors.TextTertiary,
                modifier = Modifier.size(TaskListSizes.MetaIcon),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = recurringLabel,
                color = TaskListColors.TextSecondary,
                fontSize = 13.sp,
            )
        }
        if (recurringLabel != null && projectName != null) {
            MetaSeparator()
        }
        projectName?.let { name ->
            Text(
                text = name,
                color = TaskListColors.TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MetaSeparator() {
    Spacer(Modifier.width(TaskListSpacing.Xs))
    Text(
        text = "·",
        color = TaskListColors.TextTertiary,
        fontSize = 13.sp,
    )
    Spacer(Modifier.width(TaskListSpacing.Xs))
}

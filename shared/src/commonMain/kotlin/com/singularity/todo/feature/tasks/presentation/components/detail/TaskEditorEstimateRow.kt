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
import androidx.compose.material.icons.filled.Timer
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
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import androidx.compose.material3.MaterialTheme

/**
 * Renders the estimate attribute row in the task editor.
 * Tapping the row opens the [com.singularity.todo.feature.tasks.presentation.state.TaskEditorSheet.Estimate] sheet.
 *
 * @param estimateMinutes Current estimate in minutes, or `null` if not set.
 * @param onEstimateClick Called when the row is tapped to open the estimate picker sheet.
 *   Pass `null` when [onEstimateClear] is also `null` (Create mode — sheet opened by default).
 * @param onEstimateClear Called when the X button is tapped. Pass `null` in Create mode
 *   (no X button is shown).
 */
@Composable
fun TaskEditorEstimateRow(
    estimateMinutes: Int?,
    onEstimateClick: (() -> Unit)?,
    onEstimateClear: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.TASK_EDITOR_ESTIMATE_ROW)
            .clickable(
                onClick = onEstimateClick ?: {},
            )
            .padding(
                horizontal = TaskSpacing.cardPaddingHorizontal,
                vertical = TaskSpacing.cardPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val hasEstimate = estimateMinutes != null
        val activeTint =
            if (hasEstimate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        val idleTint =
            if (hasEstimate) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(
            imageVector = Icons.Filled.Timer,
            contentDescription = null,
            tint = if (hasEstimate) activeTint else idleTint,
            modifier = Modifier.size(TaskSpacing.iconSize),
        )
        Spacer(Modifier.width(TaskSpacing.lg))
        Text(
            text = estimateRowLabel(estimateMinutes),
            color = if (hasEstimate) idleTint else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 16.sp,
            fontWeight = if (estimateMinutes != null) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (estimateMinutes != null && onEstimateClear != null) {
            IconButton(onClick = onEstimateClear) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Сбросить оценку",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

internal fun estimateRowLabel(minutes: Int?): String {
    if (minutes == null) return "Добавить оценку"
    return formatEstimate(minutes)
}

internal fun formatEstimate(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

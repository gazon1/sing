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
import androidx.compose.material.icons.outlined.CalendarToday
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
import com.singularity.todo.feature.tasks.presentation.theme.TaskColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/**
 * Renders the due date attribute row in the task editor.
 * Tapping the row opens the [com.singularity.todo.feature.tasks.presentation.state.TaskEditorSheet.Date] sheet.
 *
 * @param dueDate Current due date value, or `null` if not set.
 * @param dueTime Current due time value, or `null` if not set.
 * @param onDueDateClick Called when the row is tapped to open the date picker sheet.
 *   Pass `null` when [onDueDateClear] is also `null` (Create mode — sheet opened by default).
 * @param onDueDateClear Called when the X button is tapped. Pass `null` in Create mode
 *   (no X button is shown).
 */
@Composable
fun TaskEditorDueDateRow(
    dueDate: LocalDate?,
    dueTime: LocalTime?,
    onDueDateClick: (() -> Unit)?,
    onDueDateClear: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.TASK_EDITOR_DUE_ROW)
            .clickable(
                onClick = onDueDateClick ?: {},
            )
            .padding(
                horizontal = TaskSpacing.cardPaddingHorizontal,
                vertical = TaskSpacing.cardPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.CalendarToday,
            contentDescription = null,
            tint = if (dueDate != null) TaskColors.AccentBlue else TaskColors.TextSecondary,
            modifier = Modifier.size(TaskSpacing.iconSize),
        )
        Spacer(Modifier.width(TaskSpacing.lg))
        Text(
            text = dueDateRowLabel(dueDate, dueTime),
            color = if (dueDate != null) TaskColors.TextPrimary else TaskColors.TextSecondary,
            fontSize = 16.sp,
            fontWeight = if (dueDate != null) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (dueDate != null && onDueDateClear != null) {
            IconButton(onClick = onDueDateClear) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Сбросить дату",
                    tint = TaskColors.TextSecondary,
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

private fun dueDateRowLabel(date: LocalDate?, time: LocalTime?): String {
    if (date == null) return "Добавить дату"
    val dateStr = date.toString()
    return if (time != null) {
        "$dateStr ${time.toString().take(5)}"
    } else {
        dateStr
    }
}

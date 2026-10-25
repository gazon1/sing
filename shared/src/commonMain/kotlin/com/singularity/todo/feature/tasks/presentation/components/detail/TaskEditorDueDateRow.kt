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
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.formatRussianDateList
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import androidx.compose.material3.MaterialTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Renders the due date attribute row in the task editor.
 * Tapping the row opens the [com.singularity.todo.feature.tasks.presentation.state.TaskEditorSheet.Date] sheet.
 *
 * @param dueDate Current due date value, or `null` if not set.
 * @param dueDateTime Current due time value, or `null` if not set.
 * @param dueDates The dates of the work when it is scheduled on several of them.
 *   More than one entry changes the label to the list of dates; one or none keeps
 *   the single-date label exactly as it was.
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
    labelTestTag: String? = null,
    dueDates: ImmutableList<LocalDate> = persistentListOf(),
) {
    val dates = dueDates.ifEmpty { dueDate?.let { persistentListOf(it) } ?: persistentListOf() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.TASK_EDITOR_DUE_ROW)
            .mapTestTagsAsResourceIds()
            // No clickable() at all when the callback is null — see TaskEditorPriorityRow.
            .then(
                if (onDueDateClick != null) {
                    Modifier.clickable { onDueDateClick() }
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
        val activeTint =
            if (dueDate != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        val idleTint =
            if (dueDate != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(
            imageVector = Icons.Outlined.CalendarToday,
            contentDescription = null,
            tint = if (dueDate != null) activeTint else idleTint,
            modifier = Modifier.size(TaskSpacing.iconSize),
        )
        Spacer(Modifier.width(TaskSpacing.lg))
        val labelModifier =
            if (labelTestTag != null) Modifier.testTag(labelTestTag) else Modifier
        Text(
            text = dueDateRowLabel(dueDate, dueTime, dates),
            color = if (dueDate != null) idleTint else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 16.sp,
            fontWeight = if (dueDate != null) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f).then(labelModifier),
        )
        if (dueDate != null && onDueDateClear != null) {
            IconButton(onClick = onDueDateClear) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Сбросить дату",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

/**
 * The label of the due-date row.
 *
 * One date reads exactly as it did before the series existed — the ISO date, and
 * the time next to it. Several dates read as a list, because a row that showed
 * only the first of them would hide the fact that there are others.
 */
private fun dueDateRowLabel(date: LocalDate?, time: LocalTime?, dates: List<LocalDate>): String {
    if (date == null) return "Добавить дату"
    if (dates.size > 1) return formatRussianDateList(dates)
    val dateStr = date.toString()
    return if (time != null) {
        "$dateStr ${time.toString().take(5)}"
    } else {
        dateStr
    }
}

package com.singularity.todo.feature.calendar.presentation.components.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.SeriesMarker
import com.singularity.todo.core.ui.touchTarget
import com.singularity.todo.feature.calendar.domain.model.CalendarTaskStatus
import com.singularity.todo.feature.calendar.presentation.theme.LocalCalendarPalette

/**
 * One task row, used in the all-day strip, month grid cells, and hour grid cells.
 * Mirrors the mock's [TaskChip] — all colours come from [LocalCalendarPalette].
 */
@Composable
fun TaskChip(
    task: CalendarTask,
    isSelected: Boolean = false,
    onClick: () -> Unit = {},
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = LocalCalendarPalette.current
    val background = taskMarkerBackground(task, isSelected)

    if (compact) {
        // A chip with a title renders "Re…" in a month cell; a status-coloured
        // dot does not. The month cell is ~51 dp and holds the 48 dp target,
        // so the dot is a task control here.
        Box(
            modifier =
                modifier
                    .touchTarget()
                    .size(8.dp)
                    .background(background, CircleShape)
                    .clickable { onClick() }
                    .semantics { contentDescription = task.title }
                    .testTag(TestTags.CALENDAR_ALL_DAY_MARKER),
        )
        return
    }

    val textColor =
        when {
            isSelected -> Color.White
            task.status == CalendarTaskStatus.DONE -> palette.textMuted
            task.isLink -> palette.link
            else -> palette.textPrimary
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(background, RoundedCornerShape(4.dp))
                .clickable { onClick() }
                .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            task.status == CalendarTaskStatus.DONE -> {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = palette.textMuted,
                    modifier = Modifier.size(12.dp),
                )
            }

            task.isRecurring -> {
                Icon(
                    Icons.Default.Autorenew,
                    contentDescription = null,
                    tint = textColor.copy(alpha = 0.8f),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        if (task.status == CalendarTaskStatus.DONE || task.isRecurring) {
            Spacer(Modifier.width(4.dp))
        }
        task.emoji?.let {
            Text(it, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
        }
        if (task.isSeries) {
            // The mark of a series: the chip has no room for a word, and the
            // icon says what the row is.
            SeriesMarker(isSeries = true)
            Spacer(Modifier.width(4.dp))
        }
        val decoration =
            when {
                task.status == CalendarTaskStatus.DONE -> TextDecoration.LineThrough
                task.isLink -> TextDecoration.Underline
                else -> null
            }
        Text(
            text = task.title,
            color = textColor,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textDecoration = decoration,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/**
 * The status colour shared by a title chip and a compact marker.
 *
 * The week grid's narrow marker cannot render a title, but it must keep the
 * same colour language as the chip it replaces.
 */
@Composable
internal fun taskMarkerBackground(
    task: CalendarTask,
    isSelected: Boolean,
): Color {
    val palette = LocalCalendarPalette.current
    return when {
        isSelected -> palette.taskSelected
        task.status == CalendarTaskStatus.DONE -> palette.taskDone
        task.status == CalendarTaskStatus.OVERDUE -> palette.taskOverdue
        else -> palette.taskDefault
    }
}

/**
 * "+N more" label for tasks a cell had to hide.
 *
 * Both callers route it to the day view, which is where the full list lives. In
 * the time grid a date column is one column of a multi-day grid, not the day view
 * itself, so "open the day" is a real navigation. The one caller that passes null
 * is the single-day view, where all tasks are rendered and the label never appears.
 */
@Composable
fun MoreTasksLabel(
    count: Int,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val palette = LocalCalendarPalette.current
    Text(
        // "+N more" cannot fit a ~43 dp week column; the count alone can. The
        // full sentence lives in the month grid, where the cell is wider.
        text = if (compact) "+$count" else "+$count more",
        color = palette.textMuted,
        fontSize = 12.sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

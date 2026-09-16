package com.singularity.todo.feature.calendar.presentation.components.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    modifier: Modifier = Modifier,
) {
    val palette = LocalCalendarPalette.current
    val background = when {
        isSelected -> palette.taskSelected
        task.status == CalendarTaskStatus.DONE -> palette.taskDone
        task.status == CalendarTaskStatus.OVERDUE -> palette.taskOverdue
        else -> palette.taskDefault
    }
    val textColor = when {
        isSelected -> Color.White
        task.status == CalendarTaskStatus.DONE -> palette.textMuted
        task.isLink -> palette.link
        else -> palette.textPrimary
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            task.status == CalendarTaskStatus.DONE -> Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = palette.textMuted,
                modifier = Modifier.size(12.dp),
            )

            task.isRecurring -> Icon(
                Icons.Default.Autorenew,
                contentDescription = null,
                tint = textColor.copy(alpha = 0.8f),
                modifier = Modifier.size(12.dp),
            )
        }
        if (task.status == CalendarTaskStatus.DONE || task.isRecurring) {
            Spacer(Modifier.width(4.dp))
        }
        task.emoji?.let {
            Text(it, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
        }
        val decoration = when {
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

/** "+N more" label that expands hidden tasks in a day cell. */
@Composable
fun MoreTasksLabel(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalCalendarPalette.current
    Text(
        text = "+$count more",
        color = palette.textMuted,
        fontSize = 12.sp,
        modifier = modifier
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

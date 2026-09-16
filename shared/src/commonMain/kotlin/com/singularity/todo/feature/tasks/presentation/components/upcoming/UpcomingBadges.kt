package com.singularity.todo.feature.tasks.presentation.components.upcoming

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.feature.tasks.presentation.state.TaskBadgesUi
import com.singularity.todo.feature.tasks.presentation.theme.TaskListColors
import com.singularity.todo.feature.tasks.presentation.theme.UpcomingTokens

/**
 * Badge row for the Upcoming task row.
 *
 * Renders note, checklist, progress, timer, and deadline-flag badges
 * in a [Column]-then-[Row] layout (effectively a 2-row flow) using [AnimatedVisibility]
 * to avoid layout jumps.
 */
@Composable
fun UpcomingBadges(badges: TaskBadgesUi) {
    val hasAny = badges.hasNote || badges.checklistTotal != null ||
        badges.progressPercent != null || badges.timerMinutes != null ||
        badges.deadlineDate != null

    AnimatedVisibility(
        visible = hasAny,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(start = 8.dp),
        ) {
            // First row: progress + note + checklist
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                badges.progressPercent?.let { pct ->
                    Text(
                        text = "$pct%",
                        color = TaskListColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
                if (badges.hasNote) {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = "Has note",
                        tint = TaskListColors.TextSecondary,
                        modifier = Modifier.size(15.dp),
                    )
                }
                badges.checklistTotal?.let { total ->
                    Text(
                        text = total.toString(),
                        color = TaskListColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            // Second row: timer + deadline flag
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                badges.timerMinutes?.let { mins ->
                    Text(
                        text = "${mins}m",
                        color = TaskListColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                badges.deadlineDate?.let { date ->
                    Icon(
                        imageVector = Icons.Filled.Flag,
                        contentDescription = "Deadline",
                        tint = UpcomingTokens.AccentRed,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = formatDeadlineDate(date),
                        color = UpcomingTokens.AccentRed,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun formatDeadlineDate(date: kotlinx.datetime.LocalDate): String {
    val m = date.monthNumber.toString().padStart(2, '0')
    val d = date.dayOfMonth.toString().padStart(2, '0')
    return "$m/$d/${date.year}"
}

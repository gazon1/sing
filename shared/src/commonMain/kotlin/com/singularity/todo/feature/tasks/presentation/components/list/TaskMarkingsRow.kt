package com.singularity.todo.feature.tasks.presentation.components.list

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import com.singularity.todo.feature.tasks.domain.logic.displayOrder
import com.singularity.todo.feature.tasks.domain.model.TaskMarking
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing
import com.singularity.todo.feature.tasks.presentation.theme.TaskSemanticColors
import com.singularity.todo.feature.tasks.presentation.theme.mutedTextColor
import kotlinx.collections.immutable.ImmutableSet

/**
 * How many markings a row shows before it says how many it left out.
 *
 * Three is what fits beside the date and the project without the row wrapping
 * on a narrow phone. The count of the rest is stated rather than the markings
 * being dropped silently — a task that is blocked *and* overdue must not look
 * like a task that is only blocked.
 */
internal const val MAX_VISIBLE_MARKINGS = 3

/**
 * The markings the row already says another way.
 *
 * Two facts do not need a mark of their own, because the row shows them
 * somewhere else and a second showing would be noise:
 *
 * - **Completed** — the title is already struck through.
 * - **No date** — the absence of a date is the absence of a date. A chip
 *   saying "no date" on every task without one would be noise, not
 *   information.
 *
 * **Overdue** is *not* in this set even though the date is red. Red says "the
 * date is in the past" only to someone who already knows what red means; the
 * marking says it in words, for a screen reader, and the acceptance scenario
 * asks for the blocked and the overdue markings to be on the row at the same
 * time — which a single "special" icon could not express.
 */
private val ConveyedElsewhere = setOf(
    TaskMarking.Completed,
    TaskMarking.NoDate,
)

/**
 * The marks on a task row: what is this task marked with?
 *
 * Every marking that applies is shown, and none of them hides another. That is
 * the whole difference from the row this replaces: the old meta row had one
 * slot for "is this task special", and a task that was both blocked and
 * overdue could only be one of them.
 *
 * The order is fixed — the states the user set first, then the facts that
 * change what they can do, then the rest — so two rows with the same markings
 * look the same.
 *
 * @param markings the markings that apply to the task.
 */
@Composable
fun TaskMarkingsRow(
    markings: ImmutableSet<TaskMarking>,
    modifier: Modifier = Modifier,
) {
    val shown = markings
        .filter { it !in ConveyedElsewhere }
        .sortedBy { it.displayOrder }
    if (shown.isEmpty()) return

    val visible = shown.take(MAX_VISIBLE_MARKINGS)
    val hidden = shown.size - visible.size

    Row(modifier = modifier) {
        visible.forEach { marking ->
            Icon(
                imageVector = marking.icon(),
                contentDescription = marking.contentDescription(),
                tint = marking.tint(),
                modifier = Modifier.size(TaskListSizes.MetaIcon),
            )
            Spacer(Modifier.width(TaskListSpacing.Xs))
        }
        if (hidden > 0) {
            Text(
                text = "+$hidden",
                color = mutedTextColor(),
                fontSize = 12.sp,
            )
        }
    }
}

/** The icon that stands for this marking. */
private fun TaskMarking.icon(): ImageVector = when (this) {
    TaskMarking.InProgress -> Icons.Default.PlayArrow
    TaskMarking.NextUp -> Icons.Default.Schedule
    TaskMarking.Blocked -> Icons.Default.Block
    TaskMarking.FollowUp -> Icons.Default.Flag
    TaskMarking.Pinned -> Icons.Default.PushPin
    TaskMarking.Recurring -> Icons.Default.Repeat
    TaskMarking.Overdue -> Icons.Default.Schedule
    TaskMarking.NoDate -> Icons.Default.Schedule
    TaskMarking.Completed -> Icons.Default.Schedule
}

/**
 * What the mark means, for a screen reader.
 *
 * The icons are distinct, but distinctness is a claim about pixels; this is
 * the claim about the fact, and it is the one a screen reader gets to read.
 */
private fun TaskMarking.contentDescription(): String = when (this) {
    TaskMarking.InProgress -> "Задача в работе"
    TaskMarking.NextUp -> "Следующая задача"
    TaskMarking.Blocked -> "Задача заблокирована зависимостями"
    TaskMarking.FollowUp -> "Задача продолжает другую"
    TaskMarking.Pinned -> "Задача закреплена"
    TaskMarking.Recurring -> "Повторяющаяся задача"
    TaskMarking.Overdue -> "Просроченная задача"
    TaskMarking.NoDate -> "Задача без даты"
    TaskMarking.Completed -> "Задача выполнена"
}

/**
 * The colour of the mark.
 *
 * Two marks carry meaning beyond "this task is in some state": the blocked
 * one, because it says the user cannot start this yet, and the in-progress
 * one, because it says they have. The rest are muted — they are context, not
 * news.
 */
@Composable
private fun TaskMarking.tint(): Color = when (this) {
    TaskMarking.Blocked -> TaskSemanticColors.Danger
    TaskMarking.InProgress -> MaterialTheme.colorScheme.primary
    TaskMarking.NextUp -> MaterialTheme.colorScheme.primary
    TaskMarking.FollowUp -> MaterialTheme.colorScheme.onSecondaryContainer
    TaskMarking.Pinned -> MaterialTheme.colorScheme.primary
    TaskMarking.Recurring -> mutedTextColor()
    TaskMarking.Overdue -> TaskSemanticColors.Danger
    TaskMarking.NoDate -> mutedTextColor()
    TaskMarking.Completed -> mutedTextColor()
}

package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing
import com.singularity.todo.feature.tasks.presentation.theme.priorityMeta
import org.jetbrains.compose.resources.stringResource
import singularity_todo.shared.generated.resources.Res
import singularity_todo.shared.generated.resources.priority_none
import singularity_todo.shared.generated.resources.priority_word_high
import singularity_todo.shared.generated.resources.priority_word_low
import singularity_todo.shared.generated.resources.priority_word_medium
import singularity_todo.shared.generated.resources.priority_word_urgent

/**
 * The chip's own priority tint.
 *
 * Named `priorityChipColor` rather than `priorityColor` because a second
 * `priorityColor` already exists in the `components.list` package, carries
 * different values, and is the one the unit test covers. Two functions of the
 * same name in sibling packages differing only in their palettes is a wrong-import
 * waiting to happen — the compiler will not flag it and the reviewer will not
 * notice.
 */
internal fun priorityChipColor(priority: TaskPriority): Color = priorityMeta(priority).color

/**
 * The priority, as a shape and a readable word.
 *
 * The priority colour is a **shape**, never a text colour: Medium is amber, and
 * amber on a light surface measures 1.5:1 — text in it is unreadable (F10 of the
 * 2026-11-09 comparison). So the colour moved into a dot, and the word next to it
 * is drawn in a theme colour that keeps its contrast in both themes. The word
 * says which priority it is; the dot says what it looks like.
 */
@Composable
fun PriorityChip(
    priority: TaskPriority,
    modifier: Modifier = Modifier,
) {
    if (priority == TaskPriority.None) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(start = 4.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(TaskListSizes.MetaIcon)
                    .clip(CircleShape)
                    .background(priorityChipColor(priority)),
        )
        Spacer(Modifier.width(TaskListSpacing.Xs))
        Text(
            text = priorityWord(priority),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The chip's word for the priority, in the user's language.
 *
 * Not `priority.name`: the enum's constant is a code identifier, and the app
 * ships a Russian locale where every other priority string is translated.
 */
@Composable
private fun priorityWord(priority: TaskPriority): String =
    when (priority) {
        TaskPriority.Low -> stringResource(Res.string.priority_word_low)
        TaskPriority.Medium -> stringResource(Res.string.priority_word_medium)
        TaskPriority.High -> stringResource(Res.string.priority_word_high)
        TaskPriority.Urgent -> stringResource(Res.string.priority_word_urgent)
        TaskPriority.None -> stringResource(Res.string.priority_none)
    }

// ===== Preview =====

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun PriorityChipMediumPreview() =
    PreviewThemed(darkTheme = false) {
        PriorityChip(priority = TaskPriority.Medium)
    }

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun PriorityChipHighDarkPreview() =
    PreviewThemed(darkTheme = true) {
        PriorityChip(priority = TaskPriority.High)
    }

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun PriorityChipUrgentPreview() =
    PreviewThemed(darkTheme = false) {
        PriorityChip(priority = TaskPriority.Urgent)
    }

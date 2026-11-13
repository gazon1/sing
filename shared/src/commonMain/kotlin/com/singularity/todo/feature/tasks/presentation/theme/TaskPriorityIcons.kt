package com.singularity.todo.feature.tasks.presentation.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LowPriority
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.singularity.todo.core.ui.theme.mutedTextColorOf
import com.singularity.todo.feature.tasks.domain.model.TaskPriority
import androidx.compose.material.icons.outlined.Flag as OutlinedFlag

/*
 * One icon language for the five priority levels.
 *
 * ## Shape carries the level, colour carries the exception
 *
 * The priority palette in [TaskSemanticColors] is a set of raw hues — green, amber,
 * red — and on a light surface most of them measure below 3:1, so they cannot be
 * used as the level's only signal. The task list gets away with it because the
 * marker wears a rim; the detail screen has no rim, and the priority sheet and
 * the priority row are read side by side.
 *
 * So the level is carried by the shape, and the colour is free to say one thing
 * only: whether the level is an exception.
 *
 * - **None** — an outlined flag, muted. There is no priority to show.
 * - **Low** — a down arrow, in ordinary text colour.
 * - **Medium** — a filled flag, in ordinary text colour. Medium is the level a
 *   task has by default, so it must not look like an alarm; the accent fill it
 *   used to wear is what made it look like one.
 * - **High** — a warning triangle, in the error role.
 * - **Urgent** — a doubled chevron, in the error role.
 *
 * High and Urgent share a colour because they share a meaning: both are the task
 * asking for attention now. They are told apart by shape, and so is everything
 * else — no level of this scale is signalled by colour alone.
 */

/**
 * The glyph that stands for [priority].
 *
 * The same glyph is used by the priority row and by the priority sheet, so the two
 * read as one language rather than as two lists that happen to agree.
 */
internal fun priorityIcon(priority: TaskPriority): ImageVector =
    when (priority) {
        TaskPriority.None -> Icons.Outlined.OutlinedFlag
        TaskPriority.Low -> Icons.Filled.LowPriority
        TaskPriority.Medium -> Icons.Filled.Flag
        TaskPriority.High -> Icons.Filled.Warning
        TaskPriority.Urgent -> Icons.Filled.PriorityHigh
    }

/**
 * The colour the priority glyph is painted in.
 *
 * The error role for the two levels that are exceptions, the ordinary text colour
 * for the two that are set but ordinary, and the muted role for the one that is
 * not set at all. All three clear 4.5:1 against the surface the row stands on,
 * so the glyph is legible in either mode and on every accent.
 */
@Composable
internal fun priorityIconTint(priority: TaskPriority): Color = priorityIconTintOf(MaterialTheme.colorScheme, priority)

/**
 * The pure form of [priorityIconTint]: the colour the app actually paints for
 * [priority] under [scheme].
 *
 * The same shape as `mutedTextColorOf` — a test can measure the token the app
 * paints instead of a value it typed in and hoped for, and the tint of every
 * level can be compared against every palette without a composition around it.
 */
internal fun priorityIconTintOf(
    scheme: ColorScheme,
    priority: TaskPriority,
): Color =
    when (priority) {
        TaskPriority.None -> mutedTextColorOf(scheme)
        TaskPriority.Low, TaskPriority.Medium -> scheme.onSurface
        TaskPriority.High, TaskPriority.Urgent -> scheme.error
    }

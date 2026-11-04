package com.singularity.todo.feature.tasks.presentation.theme

import androidx.compose.ui.graphics.Color
import com.singularity.todo.feature.tasks.domain.model.TaskPriority

/*
 * Colours that answer a question about the ITEM, not about the app's appearance.
 *
 * These deliberately do NOT follow the theme. Priority is a semantic scale: how
 * urgent a task looks must not depend on which accent a user happened to pick. A
 * sweep that converted every fixed colour into a theme role would tie "this is
 * urgent" to an arbitrary preference, so two tasks of obviously different
 * importance could render in near-identical colours.
 *
 * Everything that is genuinely a theme role lives in [TaskDerivedColors] and reads
 * the colour scheme instead. This file is the boundary between the two.
 */

/**
 * Single priority scale for the entire app. All screens use the same palette;
 * any future per-screen variation must be a value change within a hue (not a
 * hue change) and documented in one place.
 */
enum class PriorityPalette {
    /** Canonical priority palette used everywhere. */
    TaskDetail,

    /** @deprecated Use [TaskDetail]. Retained for binary compatibility. */
    @Deprecated("Use TaskDetail")
    TaskList,

    /** @deprecated Use [TaskDetail]. Retained for binary compatibility. */
    @Deprecated("Use TaskDetail")
    PriorityChip,
}

data class PriorityMeta(val color: Color, val label: String)

/** One screen's worth of priority tints. */
private data class PriorityTones(
    val none: Color,
    val low: Color,
    val medium: Color,
    val high: Color,
    val urgent: Color,
)

private val PriorityColors =
    PriorityTones(
        none = Color(0xFF8B94A6),
        low = Color(0xFF6FCF97),
        medium = Color(0xFFF2C94C),
        high = Color(0xFFEB5757),
        urgent = Color(0xFFFF6B6B),
    )

/** Muted but legible on both a light and a dark surface, so it is not a theme role. */
val PriorityNoneColor: Color = PriorityColors.none

/** Destructive actions. Paired with [TaskSemanticColors.Success] for the confirm/cancel pair. */
object TaskSemanticColors {
    val Danger = Color(0xFFE5484D)
    val Success = Color(0xFF4CC38A)
}

/** Labels are the same on every screen; only the tint differs. */
internal fun priorityLabel(priority: TaskPriority): String =
    when (priority) {
        TaskPriority.None -> "No priority"
        TaskPriority.Low -> "Low priority"
        TaskPriority.Medium -> "Medium priority"
        TaskPriority.High -> "High priority"
        TaskPriority.Urgent -> "Urgent"
    }

/**
 * The single place a priority is turned into a colour and a label.
 *
 * Callers name the screen they are on rather than picking a tint by eye.
 */
internal fun priorityMeta(
    priority: TaskPriority,
    @Suppress("UNUSED_PARAMETER") palette: PriorityPalette = PriorityPalette.TaskDetail,
): PriorityMeta {
    val colors = PriorityColors
    val color =
        when (priority) {
            TaskPriority.None -> colors.none
            TaskPriority.Low -> colors.low
            TaskPriority.Medium -> colors.medium
            TaskPriority.High -> colors.high
            TaskPriority.Urgent -> colors.urgent
        }
    return PriorityMeta(color, priorityLabel(priority))
}

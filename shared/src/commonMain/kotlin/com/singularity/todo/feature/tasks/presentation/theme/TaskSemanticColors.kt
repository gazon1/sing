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
 * Priority is expressed on three screens and the palettes are, in two cases, not
 * the same. The divergence is deliberate in one case and undocumented in the
 * other — see the entries.
 */
enum class PriorityPalette {
    /** Task detail / editor. Warmer, softer reds; the editor is a form, not a list. */
    TaskDetail,

    /** Task list rows and the checkbox. Deeper reds tuned to the list's rhythm. */
    TaskList,

    /**
     * The standalone priority chip. Saturated material hues, and it is the only
     * one of the three that nobody documented: the chip grew its own `when`
     * block while the other two went through [PriorityPalette], and a second
     * function of the same name in another package shadowed the canonical one.
     * Values preserved exactly — see the deferred backlog entry on the duplicate.
     */
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

private val DetailPriority = PriorityTones(
    none = Color(0xFF8B94A6),
    low = Color(0xFF6FCF97),
    medium = Color(0xFFF2C94C),
    high = Color(0xFFEB5757),
    urgent = Color(0xFFFF6B6B),
)

private val ListPriority = PriorityTones(
    none = Color(0xFF5C6270),
    low = Color(0xFF5B8DEF),
    medium = Color(0xFFF5A623),
    high = Color(0xFFE5484D),
    urgent = Color(0xFFFF6B6B),
)

// The chip draws nothing for "no priority" — it returns early — so it has never
// needed a tint. Kept as Color.Unspecified because that is what the old `when`
// block returned; silently giving it a colour would let a future caller paint it.
private val ChipPriority = PriorityTones(
    none = Color.Unspecified,
    low = Color(0xFF4CAF50),
    medium = Color(0xFFFF9800),
    high = Color(0xFFF44336),
    urgent = Color(0xFFE91E63),
)

/** Muted but legible on both a light and a dark surface, so it is not a theme role. */
val PriorityNoneColor: Color = ListPriority.none

/** Destructive actions. Paired with [TaskSemanticColors.Success] for the confirm/cancel pair. */
object TaskSemanticColors {
    val Danger = Color(0xFFE5484D)
    val Success = Color(0xFF4CC38A)
}

/** Labels are the same on every screen; only the tint differs. */
internal fun priorityLabel(priority: TaskPriority): String = when (priority) {
    TaskPriority.None -> "No priority"
    TaskPriority.Low -> "Low priority"
    TaskPriority.Medium -> "Medium priority"
    TaskPriority.High -> "High priority"
    TaskPriority.Urgent -> "Urgent"
}

/**
 * The single place a priority is turned into a colour and a label.
 *
 * Callers name the screen they are on rather than picking a tint by eye, because
 * the three palettes are close enough that choosing the wrong one is invisible in
 * review and visible to the user.
 */
internal fun priorityMeta(
    priority: TaskPriority,
    palette: PriorityPalette = PriorityPalette.TaskDetail,
): PriorityMeta {
    val colors = when (palette) {
        PriorityPalette.TaskDetail -> DetailPriority
        PriorityPalette.TaskList -> ListPriority
        PriorityPalette.PriorityChip -> ChipPriority
    }
    val color = when (priority) {
        TaskPriority.None -> colors.none
        TaskPriority.Low -> colors.low
        TaskPriority.Medium -> colors.medium
        TaskPriority.High -> colors.high
        TaskPriority.Urgent -> colors.urgent
    }
    return PriorityMeta(color, priorityLabel(priority))
}

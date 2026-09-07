package com.singularity.todo.core.ui.components

import androidx.compose.ui.graphics.Color
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Pure-Kotlin presentation helpers used by [core/ui/components]. No Compose
 * runtime — safe to unit-test directly from `commonTest` without Robolectric.
 *
 * Feature-specific formatters (e.g. `formatAiResult` in `feature/tasks`) live
 * next to their domain types to avoid core → feature back-references.
 */

/** Hex → Compose [Color]. [0L] means "no color" / unspecified. */
internal fun hexColor(value: Long): Color =
    if (value == 0L) Color.Unspecified else Color(value)

/** Priority badge color by ordinal index. `4 == None` returns unspecified. */
internal fun priorityColorByIndex(index: Int): Color = when (index) {
    0 -> Color(0xFF4CAF50) // Low    - green
    1 -> Color(0xFFFF9800) // Medium - orange
    2 -> Color(0xFFF44336) // High   - red
    3 -> Color(0xFFE91E63) // Urgent - pink
    else -> Color.Unspecified
}

/**
 * Visual state for the due-date chip on the task detail screen.
 * Used to determine background / text colour (overdue = error, today = warning, future = neutral).
 */
internal enum class DueVisualState {
    /** Past due date. */
    Overdue,
    /** Due today. */
    Today,
    /** Future date. */
    Future,
}

/**
 * Result of [formatDueChip]. Contains the display text and the visual state
 * for the chip background / tint.
 */
internal data class DueChipModel(
    val text: String,
    val state: DueVisualState,
)

/**
 * Formats a due-date and optional time into the chip label shown on the
 * task detail screen, e.g. `"Today, 09:00"` or `"Tomorrow"`.
 * Returns `null` when [date] is `null` (no due date set).
 *
 * [today] must be the current local date (passed in to avoid hardcoding `Clock` here —
 * pure functions must not call `Clock` directly).
 */
internal fun formatDueChip(
    date: LocalDate?,
    time: String?,
    today: LocalDate,
): DueChipModel? {
    if (date == null) return null

    val datePart = when (date) {
        today -> "Today"
        today.plus(1, DateTimeUnit.DAY) -> "Tomorrow"
        today.minus(1, DateTimeUnit.DAY) -> "Yesterday"
        else -> date.toString() // "YYYY-MM-DD" — Composable layer can re-format
    }

    val fullText = if (time.isNullOrBlank()) datePart else "$datePart, $time"

    val state = when {
        date < today -> DueVisualState.Overdue
        date == today -> DueVisualState.Today
        else -> DueVisualState.Future
    }

    return DueChipModel(text = fullText, state = state)
}

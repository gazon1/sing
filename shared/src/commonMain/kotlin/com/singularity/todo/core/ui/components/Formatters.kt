package com.singularity.todo.core.ui.components

import androidx.compose.ui.graphics.Color

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

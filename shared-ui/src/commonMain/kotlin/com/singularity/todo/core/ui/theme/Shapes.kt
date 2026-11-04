package com.singularity.todo.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The app's shape scale, handed to `MaterialTheme` by [SingularityTheme].
 *
 * `MaterialTheme.shapes` was never overridden, and the two feature-local token
 * sets disagreed about the same corner: a task card was 14 dp through
 * `TaskSpacing.cardCornerRadius` and 16 dp through `TaskListShapes.CardRadius`.
 * Both now read [large], which is the 16 dp Material's own components already
 * assume from this slot, so a card, a FAB and a chip agree without a feature
 * spelling the number out.
 *
 * The values are Material's defaults, stated here on purpose. Not because the
 * app wants different corners today, but because "how round is a card" now has
 * one answer that a screen reads instead of a literal each screen invents, and
 * changing it changes every card at once. A feature that passes
 * `RoundedCornerShape(14.dp)` beside its neighbours is exactly the disagreement
 * this file removes.
 */
val AppShapes: Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

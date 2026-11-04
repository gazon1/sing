package com.singularity.todo.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** The minimum size of a touch target. Material's minimum interactive size is 48 dp. */
object TouchTarget {
    val MinSize = 48.dp
}

/**
 * Expands the hit area of a control to at least [minSize], leaving the content at its
 * own size.
 *
 * Material 3's `Modifier.minimumInteractiveComponentSize()` takes no size argument in
 * 1.12 — the minimum is a `LocalMinimumInteractiveComponentSize` composition local — so
 * the size this helper is called with is enforced here instead.
 *
 * **The order matters.** The modifier has to come BEFORE the `size(...)` that constrains
 * the visual size: `Modifier.touchTarget().size(20.dp)` keeps a 48 dp layout box around
 * a 20 dp icon, while `Modifier.size(20.dp).touchTarget()` is capped back to 20 dp,
 * because the outer size modifier constrains the whole chain.
 *
 * **What this does not prove.** The box it reports is wider than the content, but a
 * pointer in that extra band is still outside the content's own bounds, so whether it
 * reaches a control inside depends on the control's own touch handling — #2173 tracks
 * that question. Material's `Modifier.minimumInteractiveComponentSize()` is the
 * sanctioned alternative where a control needs the guarantee rather than the layout
 * reservation.
 */
fun Modifier.touchTarget(minSize: Dp = TouchTarget.MinSize): Modifier =
    this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val minPx = minSize.roundToPx()
        val width = max(placeable.width, minPx)
        val height = max(placeable.height, minPx)
        layout(width, height) {
            placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2)
        }
    }

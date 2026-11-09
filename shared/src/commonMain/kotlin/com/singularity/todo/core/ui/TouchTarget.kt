package com.singularity.todo.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * The minimum size of a touch target. Material's minimum interactive size is 48 dp.
 *
 * This is the app-wide minimum: every feature reads it from here, so a control never
 * has to reach into another feature's theme package for a number that names the whole
 * app. The floor is pinned by `TouchTargetTest` in the core UI package — the value
 * cannot be lowered silently.
 */
object TouchTarget {
    val MinSize = 48.dp
}

/**
 * Reserves at least [minSize] of layout space around a control's content, leaving the
 * content at its own size.
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
 * **What the reservation is.** The content is measured and centred inside a larger box,
 * so the box this modifier reports is wider than the content. The content's own pointer
 * handling keeps its own bounds, so a pointer in the extra band reaches the content
 * only if that content handles touches outside its own bounds — a control that needs a
 * guaranteed 48 dp pointer target has to be laid out at 48 dp. Material's
 * `Modifier.minimumInteractiveComponentSize()` is the same mechanism.
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

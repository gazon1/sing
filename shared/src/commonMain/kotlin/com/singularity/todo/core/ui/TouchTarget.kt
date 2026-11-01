package com.singularity.todo.core.ui

import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The minimum size of a touch target. Material's minimum interactive size is 48 dp. */
object TouchTarget {
    /** Material's minimum interactive size. */
    val MinSize = 48.dp
}

/**
 * Reserves at least [TouchTarget.MinSize] for the hit area, and leaves the visual
 * size to the caller. The size modifier has to come AFTER this one:
 * `minimumInteractiveComponentSize()` measures its child with the incoming
 * constraints, so a `.size(22.dp)` inside it still draws 22 dp while the node
 * reports 48 dp.
 */
fun Modifier.touchTarget(): Modifier = this.minimumInteractiveComponentSize()

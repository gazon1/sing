package com.singularity.todo.core.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The narrowest window at which a list and its detail can sit side by side.
 *
 * 840 dp is Material 3's "expanded" width class. Below it, a list that is capped
 * at 640 dp and a detail pane a reader can actually read do not both fit — one
 * of them has to be squeezed past the point where the cap means anything.
 *
 * The number lives here, once, because a second copy of it in a screen is a
 * second answer to "is this window wide?": the list would move aside at one
 * width while the pane it makes room for appeared at another.
 */
val WideLayoutMinWidth: Dp = 840.dp

/**
 * Whether [width] leaves room for a list and its detail side by side.
 *
 * Pure, so the boundary is unit-testable without a Compose runtime: [width] is
 * whatever the caller measured, and the answer is a comparison.
 */
fun isWideLayout(width: Dp): Boolean = width >= WideLayoutMinWidth

/**
 * The widest the detail pane's text column is allowed to get.
 *
 * The breakpoint above exists so that a list and a reader-width detail pane both
 * fit; a pane left uncapped undoes that on the monitor that motivated it, where
 * it grows to a thousand pixels of prose per line. The list already caps itself
 * at 640 dp (#1509); the pane is the half that needs the cap more, and it is the
 * half that was left open.
 */
val WideLayoutDetailPaneMaxWidth: Dp = 720.dp

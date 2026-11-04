package com.singularity.todo.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon

/**
 * Desktop implementation: the pointing hand while [enabled], the platform's own
 * arrow otherwise.
 *
 * The icon is set through Compose's pointer API rather than an AWT cursor so it
 * belongs to the node it is applied to — `pointerHoverIcon` restores the previous
 * icon when the pointer leaves, which a window-level cursor would not do.
 */
actual fun Modifier.pointerCursorHand(enabled: Boolean): Modifier =
    if (enabled) pointerHoverIcon(PointerIcon.Hand) else this

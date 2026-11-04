package com.singularity.todo.core.ui

import androidx.compose.ui.Modifier

/**
 * Android implementation: nothing.
 *
 * There is no pointer on a touch screen, so the platform never draws a hover
 * cursor and there is no icon to set. [enabled] is deliberately ignored rather
 * than used to pick between two identical modifiers.
 */
actual fun Modifier.pointerCursorHand(enabled: Boolean): Modifier = this

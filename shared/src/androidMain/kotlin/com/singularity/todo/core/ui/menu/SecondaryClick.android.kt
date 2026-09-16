package com.singularity.todo.core.ui.menu

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset

/**
 * Android implementation of [Modifier.onSecondaryClick].
 *
 * Android has no right-click interaction in the standard touch model, so this
 * is a no-op. Long-press is handled separately by the caller if needed.
 */
actual fun Modifier.onSecondaryClick(onClick: (DpOffset) -> Unit): Modifier = this

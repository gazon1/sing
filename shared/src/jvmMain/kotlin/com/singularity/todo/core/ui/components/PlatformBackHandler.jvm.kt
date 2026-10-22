package com.singularity.todo.core.ui.components

import androidx.compose.runtime.Composable

/**
 * JVM Desktop implementation: no-op.
 *
 * Desktop has no system back gesture — going back is a toolbar button, a
 * keyboard shortcut, or a window control, and each of those is a click that
 * the screen already handles on its own.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Nothing to listen to: there is no back gesture on the desktop.
}

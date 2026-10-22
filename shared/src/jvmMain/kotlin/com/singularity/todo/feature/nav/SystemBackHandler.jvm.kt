@file:Suppress("EmptyMethod")

package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable

/**
 * Desktop: no system back gesture.
 *
 * Navigation on Desktop goes through the toolbar and the window controls, so
 * there is nothing to intercept.
 *
 * @param enabled ignored on Desktop.
 * @param onBack ignored on Desktop.
 */
@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop has no system back gesture.
}

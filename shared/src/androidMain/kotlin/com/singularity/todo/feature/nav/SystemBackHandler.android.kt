package com.singularity.todo.feature.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * Android: the system back button and the predictive-back gesture.
 *
 * @param enabled whether the handler is active.
 * @param onBack called when the system back gesture fires while [enabled].
 */
@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

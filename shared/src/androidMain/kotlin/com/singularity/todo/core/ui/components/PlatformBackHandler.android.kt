package com.singularity.todo.core.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * Android implementation: delegates to [BackHandler].
 *
 * The activity's own dispatcher is what the navigation layer listens on, so a
 * handler registered here is consulted before the navigation's — a screen that
 * registers one keeps the back press, and a screen that does not leaves it to
 * the navigation.
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

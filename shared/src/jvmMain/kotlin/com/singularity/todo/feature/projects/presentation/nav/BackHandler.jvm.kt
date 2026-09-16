package com.singularity.todo.feature.projects.presentation.nav

import androidx.compose.runtime.Composable

/**
 * JVM Desktop has no system back gesture — this is a no-op.
 */
@Composable
actual fun ProjectsBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No-op on JVM desktop
}

@file:Suppress("EmptyMethod")

package com.singularity.todo.feature.tasks.presentation.nav

import androidx.compose.runtime.Composable

/**
 * JVM Desktop implementation: no-op since there is no system back gesture on desktop.
 */
@Composable
public actual fun TasksBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    // Desktop has no system back gesture — navigation is handled via toolbar / window controls.
}

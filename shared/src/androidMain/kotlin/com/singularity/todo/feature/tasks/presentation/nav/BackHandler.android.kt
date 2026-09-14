package com.singularity.todo.feature.tasks.presentation.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * Android implementation: delegates to [BackHandler].
 */
@Composable
public actual fun TasksBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    BackHandler(enabled = enabled, onBack = onBack)
}

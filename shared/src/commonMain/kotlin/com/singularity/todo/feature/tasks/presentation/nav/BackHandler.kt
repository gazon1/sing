package com.singularity.todo.feature.tasks.presentation.nav

import androidx.compose.runtime.Composable

/**
 * Expect declaration for intercepting the system back gesture.
 *
 * - Android: delegates to [androidx.activity.compose.BackHandler]
 * - JVM Desktop: no-op (no system back gesture on desktop)
 *
 * @param enabled Whether the handler is active. When false, the default system
 *                back behaviour is used.
 * @param onBack Called when the system back gesture is triggered while enabled.
 */
@Composable
public expect fun TasksBackHandler(enabled: Boolean, onBack: () -> Unit)

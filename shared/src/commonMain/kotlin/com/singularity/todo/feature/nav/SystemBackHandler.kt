package com.singularity.todo.feature.nav

import androidx.compose.runtime.Composable

/**
 * The system back gesture.
 *
 * One seam for every nested graph: on Android this is the system back button and
 * the predictive-back gesture; on Desktop there is no such gesture and the handler
 * does nothing.
 *
 * It replaces the per-feature `expect`/`actual` pairs (`TasksBackHandler`,
 * `ProjectsBackHandler`) that each carried the same delegation on Android and the
 * same no-op on Desktop — six files to say one thing twice.
 *
 * @param enabled whether the handler is active. When false the default system back
 *   behaviour applies.
 * @param onBack called when the system back gesture fires while [enabled].
 */
@Composable
expect fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit)

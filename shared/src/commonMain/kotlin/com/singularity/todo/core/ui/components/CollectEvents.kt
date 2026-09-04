package com.singularity.todo.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.Flow

/**
 * Collects one-shot events from a [Flow] and forwards each to [onEvent].
 *
 * Replaces the noisy `LaunchedEffect(Unit) { flow.collectLatest { ... } }` pattern
 * scattered across screens. The flow itself is used as the key so a fresh
 * collector is launched only when the upstream flow changes identity.
 */
@Composable
fun <T> CollectEvents(flow: Flow<T>, onEvent: (T) -> Unit) {
    LaunchedEffect(flow) { flow.collect(onEvent) }
}

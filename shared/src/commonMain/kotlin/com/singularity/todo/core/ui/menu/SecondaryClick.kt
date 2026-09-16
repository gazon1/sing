package com.singularity.todo.core.ui.menu

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset

/**
 * Right-click (secondary button) modifier — commonMain expectation.
 * Actual implementations bridge to platform pointer APIs.
 *
 * - JVM Desktop: uses `onPointerEvent(PointerEventType.Press)` with `isSecondaryPressed`
 * - Android: no-op (physical mouse not typical; touch long-press is handled separately)
 */
expect fun Modifier.onSecondaryClick(onClick: (DpOffset) -> Unit): Modifier

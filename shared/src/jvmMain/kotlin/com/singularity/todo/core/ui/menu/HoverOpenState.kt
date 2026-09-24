package com.singularity.todo.core.ui.menu

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

/**
 * State and helper for debounced hover-to-open behavior.
 *
 * Returns both the [interactionSource] (for use in [.hoverable]) and [isHovered]
 * (for conditional styling), and drives a debounced [onOpen] call after [delayMs]
 * when hovered while closed.
 *
 * @param delayMs milliseconds to wait before opening on hover (200 for top menu items, 300 for submenus)
 * @param isOpen whether the menu/submenu is currently open (prevents re-triggering while open)
 * @param onOpen called after [delayMs] of continuous hover when not already open
 */
@Composable
fun rememberHoverOpenState(
    delayMs: Long,
    isOpen: Boolean,
    onOpen: () -> Unit,
): HoverOpenState {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    LaunchedEffect(isHovered, isOpen) {
        if (isHovered && !isOpen) {
            delay(delayMs)
            if (isHovered) onOpen()
        }
    }

    return HoverOpenState(interactionSource, isHovered)
}

/**
 * @param interactionSource pass to [.hoverable] modifier
 * @param isHovered current hover state for conditional styling
 */
data class HoverOpenState(
    val interactionSource: MutableInteractionSource,
    val isHovered: Boolean,
)

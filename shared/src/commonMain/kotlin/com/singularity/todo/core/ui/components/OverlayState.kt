package com.singularity.todo.core.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * State holder coordinating a single active sheet/dialog overlay, an overflow menu toggle,
 * and a [SnackbarHostState] for transient notifications.
 *
 * Used to replace the common pattern of three independent `remember` flags:
 * ```kotlin
 * // Before — three independent flags, easy to leave inconsistent
 * var showSheet by remember { mutableStateOf<Sheet?>(null) }
 * var overflowOpen by remember { mutableStateOf(false) }
 * val snackbarHostState = remember { SnackbarHostState() }
 *
 * // After — one holder, coordinated transitions
 * val overlay = rememberOverlayState<Sheet>()
 * overlay.show(Sheet.PickColor)
 * overlay.dismiss()
 * overlay.toggleOverflow()
 * ```
 *
 * @param S The sheet/dialog type, typically a sealed interface.
 */
@Stable
class OverlayState<S : Any> {
    private var active by mutableStateOf<S?>(null)
    private var overflowOpen by mutableStateOf(false)

    /** The currently active sheet/dialog, or `null` if none is shown. */
    val sheet: S? get() = active

    /** Whether the overflow menu is currently open. */
    val isOverflowOpen: Boolean get() = overflowOpen

    /** The [SnackbarHostState] for this overlay. */
    val snackbarHostState: SnackbarHostState = SnackbarHostState()

    /**
     * Shows the given sheet/dialog, automatically closing the overflow menu first.
     */
    fun show(sheet: S) {
        active = sheet
        overflowOpen = false
    }

    /** Dismisses any active sheet/dialog. */
    fun dismissSheet() {
        active = null
    }

    /**
     * Toggles the overflow menu. Opening the overflow menu automatically dismisses
     * any active sheet.
     */
    fun toggleOverflow() {
        overflowOpen = !overflowOpen
        if (overflowOpen) active = null
    }

    /** Closes the overflow menu. */
    fun dismissOverflow() {
        overflowOpen = false
    }

    /** Dismisses everything: both the active sheet and the overflow menu. */
    fun dismissAll() {
        active = null
        overflowOpen = false
    }
}

/**
 * Creates an [OverlayState] scoped to the current composable.
 * The state survives recomposition but not process death.
 */
@Composable
fun <S : Any> rememberOverlayState(): OverlayState<S> = remember { OverlayState() }

package com.singularity.todo.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * State holder for a single active dialog/sheet overlay.
 *
 * ```
 * val dialogs = rememberDialogState<ActiveDialog>()
 *
 * dialogs.show(ActiveDialog.ConfirmDelete)
 * dialogs.dismiss()
 * dialogs.active  // currently shown dialog, or null
 * ```
 *
 * @param T The dialog/sheet type, typically a sealed interface.
 */
class DialogState<T : Any> {
    private var current: T? by mutableStateOf(null)

    /** The currently active dialog, or `null` if none is shown. */
    val active: T? get() = current

    /** Shows the given dialog. */
    fun show(dialog: T) { current = dialog }

    /** Dismisses any active dialog. */
    fun dismiss() { current = null }
}

/**
 * Creates a [DialogState] scoped to the current composable.
 * The state survives recomposition but not process death.
 */
@Composable
fun <T : Any> rememberDialogState(): DialogState<T> = remember { DialogState() }

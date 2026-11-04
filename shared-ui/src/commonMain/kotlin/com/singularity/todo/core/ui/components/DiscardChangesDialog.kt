package com.singularity.todo.core.ui.components

import androidx.compose.runtime.Composable

/**
 * Confirmation dialog shown when the user tries to navigate away from an unsaved edit.
 *
 * Dismissing it — the "keep editing" button, the back gesture, a tap outside — always
 * means [onKeepEditing]. Nothing about leaving the dialog is a decision to throw work
 * away: only the explicit discard button reaches [onDiscard]. A test that closes the
 * dialog with the dismiss action and expects the editor to still be there is therefore
 * asserting the intended behaviour, not a quirk.
 *
 * @param onDiscard          Called when the user confirms discarding.
 * @param onKeepEditing     Called when the user chooses to keep editing, or dismisses.
 * @param title              Dialog title text.
 * @param text               Dialog body text.
 * @param discardButtonText  Label for the confirm/discard button.
 * @param keepEditingButtonText Label for the cancel/keep-editing button.
 */
@Composable
fun DiscardChangesDialog(
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
    title: String = "Discard changes?",
    text: String = "You have unsaved changes. If you leave now, your changes will be lost.",
    discardButtonText: String = "Discard",
    keepEditingButtonText: String = "Keep editing",
) = ConfirmActionDialog(
    title = title,
    text = text,
    confirmButtonText = discardButtonText,
    onConfirm = onDiscard,
    onDismiss = onKeepEditing,
    dismissButtonText = keepEditingButtonText,
)

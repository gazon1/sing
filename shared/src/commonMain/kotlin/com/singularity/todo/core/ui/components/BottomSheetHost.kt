package com.singularity.todo.core.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Standard bottom sheet host for the project. Owns the [SheetState] and the
 * show-on-mount [LaunchedEffect] so callers can pass pure content lambdas.
 *
 * Sheet is open by default; parent screens control visibility by conditionally
 * composing this composable (typically guarded by `DialogState.active == X`).
 *
 * @param onDismiss Called when the user swipes the sheet down or taps outside.
 * @param sheetState Optional sheet state — defaults to a state that shows on mount.
 * @param content The sheet content — composes inside the [ModalBottomSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetHost(
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
    content: @Composable () -> Unit,
) {
    LaunchedEffect(Unit) { sheetState.show() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        content()
    }
}

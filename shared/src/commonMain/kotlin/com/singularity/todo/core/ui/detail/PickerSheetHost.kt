package com.singularity.todo.core.ui.detail

import androidx.compose.runtime.Composable
import com.singularity.todo.core.ui.components.OverlayState

/**
 * The sheet host for document-style detail screens.
 *
 * Takes a sealed interface of sheet variants (`S`) and renders the appropriate sheet
 * for each active variant. Wraps [BottomSheetHost] from `core/ui/components/sheet/`.
 *
 * Replaces the pattern of one `remember { mutableStateOf<S?>(null) }` per screen,
 * and the manual `when (sheet)` dispatch in `ProjectDetailSheetsHost` / `TaskEditorSheetsHost`.
 *
 * @param S The sealed sheet interface type (e.g. `ProjectDetailSheet`).
 * @param overlayState The [OverlayState] holding the active sheet.
 * @param sheetContent A `@Composable` lambda that renders a sheet for each `S` variant.
 */
@Composable
fun <S : Any> PickerSheetHost(
    overlayState: OverlayState<S>,
    sheetContent: @Composable (sheet: S, onDismiss: () -> Unit) -> Unit,
) {
    val activeSheet = overlayState.sheet
    if (activeSheet != null) {
        sheetContent(activeSheet) { overlayState.dismissSheet() }
    }
}

package com.singularity.todo.core.ui.components.sheet

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetHost(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dismissWithAnimation: () -> Unit = {
        scope.launch { sheetState.hide() }
            .invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = dismissWithAnimation,
        sheetState = sheetState,
        // The sheet renders into its own Android window, so the app-root
        // `testTagsAsResourceId` never reaches it and every testTag inside the
        // sheet is invisible to UI automation. The mapping is asserted *here*,
        // on the window that owns the content, rather than in each sheet's own
        // file: the content of a sheet is usually written somewhere else
        // entirely, and a mapping that has to be repeated at every such site is
        // a mapping that will be forgotten at one of them.
        //
        // The property is read from the node's *ancestors* (Compose walks up
        // the semantics tree of the window), so asserting it on the sheet's
        // root covers the whole subtree — measured on the device: the ids
        // inside `ModalBottomSheet` reach UIAutomator as resource-ids.
        modifier = modifier
            // A sheet that holds a text field has to lift above the keyboard.
            //
            // Without this the sheet stays where it is and the keyboard covers
            // it: measured on the device, the attachments sheet's URL field sat
            // at y=1048..1160, its title field at y=1176..1288 and the keyboard
            // covered everything below y=1000. The fields were on the screen and
            // could not be reached — the sheet's own text, with the keyboard on
            // top of it.
            //
            // Material3 documents this on ModalBottomSheet: `Modifier.imePadding`
            // belongs on the sheet's modifier.
            .imePadding()
            .mapTestTagsAsResourceIds(),
        contentWindowInsets = { WindowInsets.safeDrawing },
    ) {
        content()
    }
}

package com.singularity.todo.core.ui.components.sheet

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.singularity.todo.core.ui.isDesktopPlatform
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.launch

/**
 * The sheet state every sheet in the app asks for.
 *
 * ## Why the desktop half has no "partially expanded" state
 *
 * On the desktop the sheet is a *window of its own* — Material's
 * `ModalBottomSheet` renders a `Dialog` there — and it is as tall as that
 * window: the sheet's content is measured against the window's height and the
 * sheet is anchored to the window's bottom edge. The half-expanded anchor
 * therefore hangs half of the sheet *below* the window. Measured in a 360x768
 * desktop window, the sheet's body sat at y=432..1152: its lower half was
 * off-screen, so the switches there — and the actions at the end of the scroll —
 * were drawn where no one could reach them.
 *
 * The expanded anchor is `windowHeight - sheetHeight`, so the sheet fits inside
 * the window and opens with its content — and its actions — in reach.
 *
 * Android keeps the half-expanded sheet: there the bottom sheet is a window of
 * its own as well, but it is anchored to the screen and its height is a
 * fraction of the screen, so it hangs *inside* the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberAppSheetState(
    initialValue: SheetValue = SheetValue.Hidden,
    enabledValues: ImmutableSet<SheetValue> = DEFAULT_SHEET_VALUES,
): SheetState =
    rememberBottomSheetState(
        initialValue = initialValue,
        // The desktop app has no half-expanded sheet; everything else keeps
        // the states it asked for.
        enabledValues =
            if (isDesktopPlatform) {
                enabledValues - SheetValue.PartiallyExpanded
            } else {
                enabledValues
            },
    )

/**
 * The states a sheet may settle in unless it asks for others.
 *
 * A top-level value rather than a default argument: a default argument is
 * evaluated at every call, and this one is a collection.
 */
@OptIn(ExperimentalMaterial3Api::class)
private val DEFAULT_SHEET_VALUES: ImmutableSet<SheetValue> =
    persistentSetOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetHost(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberAppSheetState(initialValue = SheetValue.Hidden),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dismissWithAnimation: () -> Unit = {
        scope
            .launch { sheetState.hide() }
            .invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = dismissWithAnimation,
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
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
        modifier =
            modifier
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

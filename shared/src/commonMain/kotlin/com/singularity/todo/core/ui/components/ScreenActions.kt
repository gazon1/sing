package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Holder for the current screen's TopAppBar actions on platforms where the
 * shell owns the only bar (Android). Screens publish their actions through the
 * platform-aware [TopAppBar]/[BackTopAppBar] wrappers; the Android shell reads
 * [actions] and renders them in its own bar.
 */
class ScreenActionsHolder {
    var actions: (@Composable RowScope.() -> Unit)? by mutableStateOf(null)

    // The bar a screen owns. On Android the shell owns the ONLY bar, so a
    // screen that draws its own header (the calendar's month, the settings
    // title) publishes that header here and the shell draws it INSTEAD of its
    // own title and back button. Two bars would be two bars.
    //
    // `null` means "the screen has no opinion here", and the shell keeps what
    // it has. That is not the same as `override`: a screen that publishes a
    // title and no navigation icon wants its title in the bar and the shell's
    // own back button — the shell's back button knows where the shell is.
    var title: (@Composable () -> Unit)? by mutableStateOf(null)
    var navigationIcon: (@Composable () -> Unit)? by mutableStateOf(null)

    // Whether the published title and navigation icon replace the shell's own.
    var override: Boolean by mutableStateOf(false)

    // Selection-mode state for the Android shell bar: when set, the shell shows
    // "N selected" with an exit-selection back button instead of the normal bar.
    var selectionTitle: String? by mutableStateOf(null)
    var onExitSelection: (() -> Unit)? by mutableStateOf(null)
}

val LocalScreenActions = compositionLocalOf<ScreenActionsHolder?> { null }

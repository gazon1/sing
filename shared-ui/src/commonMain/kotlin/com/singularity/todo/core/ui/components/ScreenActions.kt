package com.singularity.todo.core.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

/**
 * Which of the three candidates fills the shell bar's title slot.
 *
 * The bar has one title and three candidates for it: the selection count, a
 * title a screen published, and the destination's own name. The precedence
 * between them lived inline in the Android shell's bar, where no test could
 * reach it; it lives here, and the bar draws whatever this answers.
 */
sealed interface ShellBarTitleSource {
    /** Selection mode: the bar says how many items are selected. */
    data class Selection(val text: String) : ShellBarTitleSource

    /** The screen's own title: the bar draws the composable the screen published. */
    data object Published : ShellBarTitleSource

    /** Nothing else wins: the bar says where the user is. */
    data class Destination(val text: String) : ShellBarTitleSource
}

/**
 * Decides what the shell bar's title says.
 *
 * [overrideShell] is the screen's "replace the shell's title" flag and
 * [hasPublishedTitle] says whether it actually published one. The flag alone
 * does not win: a screen that asked for the override and published nothing
 * leaves the destination's name in place, and so does a screen that published a
 * title without asking — the bar must not be handed a title it was not told to
 * use. That last combination is the one nothing pinned, and the one that once
 * lost the agenda's greeting.
 *
 * Precedence: [selectionTitle] wins over everything, then a published title
 * taken with the override, otherwise [destinationTitle].
 */
fun resolveShellBarTitleSource(
    selectionTitle: String?,
    overrideShell: Boolean,
    hasPublishedTitle: Boolean,
    destinationTitle: String,
): ShellBarTitleSource =
    when {
        selectionTitle != null -> ShellBarTitleSource.Selection(selectionTitle)
        overrideShell && hasPublishedTitle -> ShellBarTitleSource.Published
        else -> ShellBarTitleSource.Destination(destinationTitle)
    }

val LocalScreenActions = compositionLocalOf<ScreenActionsHolder?> { null }

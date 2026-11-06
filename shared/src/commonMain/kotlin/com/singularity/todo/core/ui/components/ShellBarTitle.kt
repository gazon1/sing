package com.singularity.todo.core.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * The title the shared bar shows.
 *
 * Precedence: the selection count, then the title the screen on screen
 * published, then the shell's own destination name. A screen that owns its bar
 * publishes a title through [ShellOverride] or the `overrideShell` parameter of
 * [TopAppBar]; the month the calendar is on says more than the word "Calendar".
 *
 * It lives beside the holder rather than inside the Android shell so the
 * precedence is one rule the shell and the tests can both render.
 */
@Composable
fun ShellBarTitle(
    screenActions: ScreenActionsHolder,
    destinationTitle: String,
) {
    val selection = screenActions.selectionTitle
    val publishedTitle = screenActions.title
    when {
        selection != null -> Text(selection)
        screenActions.override && publishedTitle != null -> publishedTitle.invoke()
        else -> Text(destinationTitle)
    }
}

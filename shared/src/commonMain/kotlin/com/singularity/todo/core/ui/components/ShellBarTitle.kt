package com.singularity.todo.core.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.singularity.todo.core.ui.TestTags

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
 *
 * The shell's own name carries [TestTags.SHELL_BAR_TITLE]. A published title
 * carries the tag only if the screen that published it tagged the text: the
 * shell draws that text as it is, and the tag is the publisher's. A screen
 * that publishes an untagged title — or one that publishes under a tag of its
 * own, like the calendar's month — leaves the bar without this tag.
 */
@Composable
fun ShellBarTitle(
    screenActions: ScreenActionsHolder,
    destinationTitle: String,
) {
    val selection = screenActions.selectionTitle
    val publishedTitle = screenActions.title
    when {
        selection != null -> Text(selection, modifier = Modifier.testTag(TestTags.SHELL_BAR_TITLE))
        screenActions.override && publishedTitle != null -> publishedTitle.invoke()
        else -> Text(destinationTitle, modifier = Modifier.testTag(TestTags.SHELL_BAR_TITLE))
    }
}

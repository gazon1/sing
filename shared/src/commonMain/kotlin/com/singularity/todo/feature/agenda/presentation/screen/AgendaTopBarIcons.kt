package com.singularity.todo.feature.agenda.presentation.screen

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The glyphs of the agenda top bar's three actions.
 *
 * Three metaphors that cannot be mistaken for one another at the size a top bar
 * draws them:
 *
 * - **multi date** — a calendar range: "put a task on several dates";
 * - **saved views** — a list: "the views you saved";
 * - **save current** — a bookmark with a plus: "add this agenda to the saved
 *   views".
 *
 * The first pair was a filled bookmark next to an outlined one. Both said
 * "bookmark", the difference was a hairline the eye cannot resolve on a phone,
 * and nothing on screen said which button opened the list and which saved the
 * view that was open. The metaphors are separate now, and the "saved views"
 * button carries the same list glyph as the saved-views destination itself, so
 * the button and the screen it opens agree.
 *
 * The icons are named here rather than written inline in the composable, so a
 * test can hold the rule that they differ.
 */
internal object AgendaTopBarIcons {
    /** "Task on several dates" — a calendar range. */
    val multiDate: ImageVector = Icons.Outlined.DateRange

    /** "Saved views" — the list of saved views. */
    val savedViews: ImageVector = Icons.Filled.ViewList

    /** "Save current agenda" — add this agenda to the saved views. */
    val saveCurrent: ImageVector = Icons.Filled.BookmarkAdd
}

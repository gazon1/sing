package com.singularity.todo.feature.agenda.presentation.screen

import androidx.compose.ui.graphics.vector.ImageVector
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The glyphs of the agenda top bar's three actions.
 *
 * The bar carries three unlabelled buttons, so the glyph is the only thing that
 * says what a button does. Two of them were bookmarks — one filled, one
 * outlined — which differ by a hairline nobody can resolve on a phone. The
 * metaphors are separate now, and this test holds them apart.
 */
@Tag("fast")
class AgendaTopBarIconsTest {
    private val actionIcons =
        listOf(
            AgendaTopBarIcons.multiDate,
            AgendaTopBarIcons.savedViews,
            AgendaTopBarIcons.saveCurrent,
        )

    @Test
    fun theThreeActionsUseThreeDifferentGlyphs() {
        val shared = actionIcons.groupBy { it.glyphName() }.filterValues { it.size > 1 }
        assertTrue(
            shared.isEmpty(),
            "Agenda top bar actions sharing one glyph: ${shared.values.flatten()}",
        )
    }

    @Test
    fun noActionUsesTheDoneCheckmark() {
        actionIcons.forEach { icon ->
            assertTrue(
                icon.glyphName() != "Check",
                "The agenda top bar draws the done checkmark: $icon",
            )
        }
    }

    /**
     * "Saved views" and "Save current agenda" are two different jobs, so they are
     * two different metaphors.
     *
     * Distinct names are not enough on their own: `Bookmark` and `BookmarkBorder`
     * have distinct names and are one silhouette, which is exactly the pair this
     * bar used to draw. So the list button has to be a list, and the save button
     * has to not be a second bookmark.
     */
    @Test
    fun theSavedViewsButtonIsAListAndTheSaveButtonIsNotASecondBookmark() {
        // Any list will do — the point is that it is a list, not a bookmark. Pinning
        // one list glyph would fail on a legitimate swap to another.
        val listGlyphs = setOf("List", "ListAlt", "ViewList", "FormatListBulleted", "Subject")
        assertTrue(
            AgendaTopBarIcons.savedViews.glyphName() in listGlyphs,
            "The saved-views button no longer carries the list it opens " +
                "(${AgendaTopBarIcons.savedViews.glyphName()})",
        )
        // One bookmark may stay — the one that creates a view. What may not come
        // back is a second plain bookmark next to it.
        val plainBookmarks = setOf("Bookmark", "BookmarkBorder", "Bookmarks")
        assertTrue(
            AgendaTopBarIcons.saveCurrent.glyphName() !in plainBookmarks,
            "The save button is a plain bookmark again, next to the list: two bookmark " +
                "silhouettes in one bar is the defect the split replaced " +
                "(${AgendaTopBarIcons.saveCurrent.glyphName()})",
        )
    }

    /**
     * The name of a glyph, without the style it is drawn in.
     *
     * "Filled.Bookmark" and "Outlined.Bookmark" are one glyph in two styles, so
     * the style is dropped before the comparison: the same shape twice is the
     * defect, whichever style it is drawn in.
     */
    private fun ImageVector.glyphName(): String = name.substringAfterLast('.')
}

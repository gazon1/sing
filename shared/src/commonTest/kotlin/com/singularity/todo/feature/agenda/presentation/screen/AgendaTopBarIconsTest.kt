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
     * The name of a glyph, without the style it is drawn in.
     *
     * "Filled.Bookmark" and "Outlined.Bookmark" are one glyph in two styles, so
     * the style is dropped before the comparison: the same shape twice is the
     * defect, whichever style it is drawn in.
     */
    private fun ImageVector.glyphName(): String = name.substringAfterLast('.')
}

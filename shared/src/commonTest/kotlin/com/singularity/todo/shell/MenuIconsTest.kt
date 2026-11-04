package com.singularity.todo.shell

import androidx.compose.ui.graphics.vector.ImageVector
import com.singularity.todo.feature.nav.AppDestination
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The glyphs of the menu.
 *
 * The menu is read as a list: the eye finds a row by its glyph before it reads
 * the label. Two rows with the same glyph turn that into reading, and a row
 * whose glyph is the "done" tick says "completed" about a screen that completes
 * nothing.
 *
 * The entries under test are the ones the menu actually draws — the account
 * section (including the entry an anonymous session adds), the search entry and
 * the destinations — taken from [AppMenu] rather than written out here, so a
 * menu entry added later is covered by this test rather than by a list that
 * drifted from the menu.
 */
@Tag("fast")
class MenuIconsTest {
    /** Every destination the menu draws, once each. */
    private val drawnDestinations: List<AppDestination> =
        (AppMenu.accountEntries(anonymous = true) + AppMenu.search + AppMenu.sheetDestinations)
            .map { it.destination }
            .distinct()

    @Test
    fun noTwoMenuEntriesShareAnIcon() {
        val byIcon = drawnDestinations.groupBy { it.icon.glyphName() }
        val shared = byIcon.filterValues { it.size > 1 }
        assertTrue(
            shared.isEmpty(),
            "Menu entries sharing one glyph: ${shared.values.flatten()}",
        )
    }

    @Test
    fun noMenuEntryUsesTheDoneCheckmark() {
        drawnDestinations.forEach { destination ->
            assertTrue(
                destination.icon.glyphName() != "Check",
                "The menu entry $destination is drawn with the done checkmark",
            )
        }
    }

    /**
     * The name of a glyph, without the style it is drawn in.
     *
     * "Filled.Search" and "Outlined.Search" are one glyph in two styles, so the
     * style is dropped before the comparison: the same shape twice is the
     * defect, whichever style it is drawn in.
     */
    private fun ImageVector.glyphName(): String = name.substringAfterLast('.')
}

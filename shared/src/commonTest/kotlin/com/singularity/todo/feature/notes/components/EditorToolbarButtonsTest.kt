package com.singularity.todo.feature.notes.components

import com.singularity.todo.feature.notes.EditorAction
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the formatting bar draws.
 *
 * Two of the four defects this covers were decisions rather than wiring, so they
 * are worth a test of their own: H1, H2 and H3 all drew `Icons.Filled.Title`, so
 * the three levels were indistinguishable inside the menu, and the row held
 * seven formatting buttons, which with Undo, Redo, the menu and AI is eleven
 * 48 dp targets in one non-scrolling row — 528 dp on a 360 dp phone.
 *
 * A test that renders the bar proves it fits; these tests prove the two
 * decisions that keep it fitting, so a later edit cannot quietly undo them.
 */
@Tag("fast")
class EditorToolbarButtonsTest {
    @Test
    fun `the three heading levels have three different glyphs`() {
        val glyphs =
            listOf(EditorAction.H1, EditorAction.H2, EditorAction.H3)
                .map { toolbarButtonFor(it).glyph }

        assertEquals(listOf("H1", "H2", "H3"), glyphs)
    }

    @Test
    fun `the bar's own row leaves room for the four fixed controls`() {
        // Undo, Redo, the overflow trigger and AI are fixed at the end of the row.
        // A 360 dp phone fits seven 48 dp targets, so the row can hold three
        // formatting actions and no more; the fourth has to come from the menu.
        val fixed = 4
        val targetsOnA360DpPhone = 7

        assertTrue(
            EditorAction.primary.size + fixed <= targetsOnA360DpPhone,
            "the row holds ${EditorAction.primary.size} formatting actions plus $fixed fixed ones",
        )
    }

    @Test
    fun `no two actions share a spoken label`() {
        val labels = (EditorAction.primary + EditorAction.overflow).map { toolbarButtonFor(it).label }

        assertEquals(labels.size, labels.toSet().size, "two actions answer to the same name: $labels")
    }
}

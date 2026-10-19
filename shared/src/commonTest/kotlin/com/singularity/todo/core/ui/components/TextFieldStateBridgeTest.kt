package com.singularity.todo.core.ui.components

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guards that keep a state-based field and a ViewModel-owned string in agreement.
 *
 * These are the rules the migration's bridge rests on, and they are the rules that keep a
 * keystroke from being reverted: a lagging upstream must not overwrite what the user is typing,
 * and a value the upstream already holds must not be written back to it.
 */
@Tag("fast")
class TextFieldStateBridgeTest {

    @Test
    fun pullIgnoresTheValueTheFieldJustHandedOut() {
        // The field pushed "abc" upstream; the upstream now holds "abc". Writing it back
        // would be a no-op at best, and at worst a cursor jump on every keystroke.
        assertFalse(shouldPullExternalText(externalText = "abc", lastEmitted = "abc", currentText = "abc"))
    }

    @Test
    fun pullAppliesAValueThatCameFromOutsideTheField() {
        // The upstream rewrote the title to something the field never sent.
        assertTrue(shouldPullExternalText(externalText = "rewritten", lastEmitted = "abc", currentText = "abc"))
    }

    @Test
    fun pullIgnoresAValueTheFieldAlreadyHolds() {
        // The upstream caught up on its own, without the field having pushed this value.
        assertFalse(shouldPullExternalText(externalText = "abc", lastEmitted = "", currentText = "abc"))
    }

    @Test
    fun pullAppliesWhenTheUpstreamClearedTheValue() {
        // The ViewModel cleared the field from the outside. That is a real change, not an echo.
        assertTrue(shouldPullExternalText(externalText = "", lastEmitted = "abc", currentText = "abc"))
    }

    @Test
    fun pullAppliesWhenTheUpstreamClearedASeededValue() {
        // The field was seeded with a loaded value and never edited. The upstream clearing
        // its own copy is a change from outside the field, so the field has to follow it —
        // otherwise the field keeps showing a title the app no longer holds.
        assertTrue(
            shouldPullExternalText(externalText = "", lastEmitted = "loaded title", currentText = "loaded title"),
        )
    }

    @Test
    fun pullIgnoresAValueTheUpstreamLaggedBehind() {
        // The user typed "abc" and then deleted back to "a"; both texts were pushed upstream.
        // An upstream that now hands back "abc" is behind, not rewriting — applying it would
        // undo the deletion the user just made with a value the app already moved past.
        assertFalse(
            shouldPullExternalText(
                externalText = "abc",
                lastEmitted = "a",
                currentText = "a",
                pushedValues = listOf("abc", "a"),
            ),
        )
    }

    @Test
    fun pullAppliesAValueTheFieldNeverPushed() {
        // The upstream rewrote the field to something the field never sent — not even
        // earlier, and not as its seed. That is a rewrite, and it must be applied.
        assertTrue(
            shouldPullExternalText(
                externalText = "rewritten",
                lastEmitted = "a",
                currentText = "a",
                pushedValues = listOf("abc", "a"),
            ),
        )
    }

    @Test
    fun pullAppliesAValueThatWasOnlyTheSeed() {
        // The seed came from the upstream, so the upstream holds that value by itself and
        // never received it as a push. Clearing it is a change from outside the field.
        assertTrue(
            shouldPullExternalText(
                externalText = "",
                lastEmitted = "abc",
                currentText = "abc",
                pushedValues = listOf("abc"),
            ),
        )
    }

    @Test
    fun pushSkipsTheValueTheUpstreamAlreadyHolds() {
        // The field was seeded from the upstream. Pushing the seed back would mark the
        // upstream dirty for a value it already has.
        assertFalse(shouldPushFieldText(fieldText = "abc", externalText = "abc"))
    }

    @Test
    fun pushSendsAnEditedValue() {
        assertTrue(shouldPushFieldText(fieldText = "abcd", externalText = "abc"))
    }

    @Test
    fun pushSendsAClearedValue() {
        // Clearing the field is an edit, and it has to reach the upstream or the upstream
        // keeps a value the user has deleted.
        assertTrue(shouldPushFieldText(fieldText = "", externalText = "abc"))
    }
}

package com.singularity.todo.core.ui.textfields

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
    fun pullAppliesADeliberateRewriteEvenWhenTheFieldPushedThatValue() {
        // The upstream declares a rewrite: the value it sends is one the field pushed
        // earlier and has moved on from. Without the declaration the bridge would read
        // this as a lagging echo and keep the field's newer text — which is the wrong
        // winner when the upstream meant to replace the value.
        assertTrue(
            shouldPullExternalText(
                externalText = "abc",
                lastEmitted = "abcd",
                currentText = "abcd",
                pushedValues = listOf("a", "ab", "abc"),
                isDeliberateRewrite = true,
            ),
        )
    }

    @Test
    fun pullSkipsADeliberateRewriteTheFieldAlreadyShows() {
        // A rewrite to the text the field already holds has nothing to write.
        assertFalse(
            shouldPullExternalText(
                externalText = "abcd",
                lastEmitted = "abcd",
                currentText = "abcd",
                pushedValues = listOf("abcd"),
                isDeliberateRewrite = true,
            ),
        )
    }

    @Test
    fun pullStillIgnoresALaggingEchoWithoutTheDeclaration() {
        // The same value, without the rewrite declaration, stays an echo: the lagging
        // case must not be lost to the mechanism that rescues the deliberate one.
        assertFalse(
            shouldPullExternalText(
                externalText = "abc",
                lastEmitted = "abcd",
                currentText = "abcd",
                pushedValues = listOf("a", "ab", "abc"),
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

    @Test
    fun flushPushesThePendingEditWhenTheUpstreamIsBehind() {
        // The upstream still holds the value before the last keystroke — the field pushed
        // it, the upstream has not applied it yet. The edit is pending, and it must go
        // out when the field leaves the composition.
        assertTrue(
            shouldFlushOnDispose(
                fieldText = "abcd",
                externalText = "abc",
                pushedValues = listOf("a", "ab", "abc"),
            ),
        )
    }

    @Test
    fun flushDoesNotResurrectADiscardedDraft() {
        // The upstream cleared the field from the outside — a discarded draft. Its value
        // is not one the field pushed, so the upstream is ahead, not behind, and the text
        // on screen is the text that was thrown away.
        assertFalse(
            shouldFlushOnDispose(
                fieldText = "abc",
                externalText = "",
                pushedValues = listOf("a", "ab"),
            ),
        )
    }

    @Test
    fun flushSkipsAValueTheUpstreamAlreadyHolds() {
        // Nothing is pending: the upstream already has this text.
        assertFalse(
            shouldFlushOnDispose(
                fieldText = "abc",
                externalText = "abc",
                pushedValues = listOf("a", "ab", "abc"),
            ),
        )
    }

    @Test
    fun flushDeclinesWhenTheUpstreamNeverReceivedAPush() {
        // The accepted price of the flush, pinned.
        //
        // The user typed one character and left in the same frame: the push is
        // asynchronous, so the upstream never received *any* value from the field and
        // holds something the field never pushed. The flush declines, and that one
        // character is lost.
        //
        // The alternative — pushing on every dispose — would resurrect a discarded
        // draft every time a user cleared a field and navigated away, which is a far
        // larger loss than one character. The trade-off is accepted; this test is what
        // "accepted" means: the behaviour is deliberate, not an accident, and a change
        // to it has to change this test on purpose.
        assertFalse(
            shouldFlushOnDispose(
                fieldText = "a",
                externalText = "",
                pushedValues = emptyList(),
            ),
        )
    }
}

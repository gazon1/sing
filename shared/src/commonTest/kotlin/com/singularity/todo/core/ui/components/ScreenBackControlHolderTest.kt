package com.singularity.todo.core.ui.components

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Who owns the back control on the screen that is on top.
 *
 * The holder is what keeps the Desktop shell's back control and a screen's own
 * back arrow apart: the shell draws its control only while nobody owns one, and
 * a screen draws its own only while it is the owner. Both read the same fact, so
 * the two can never be on screen together — not even in the frame in which the
 * screen on top is still publishing.
 */
@Tag("fast")
class ScreenBackControlHolderTest {
    @Test
    fun nobody_owns_the_control_before_a_bar_claims_it() {
        assertNull(ScreenBackControlHolder().owner)
    }

    @Test
    fun the_bar_that_draws_a_control_owns_it() {
        val holder = ScreenBackControlHolder()
        val bar = Any()

        holder.publish(bar, ownsControl = true)

        assertSame(bar, holder.owner)
    }

    /**
     * The bar that came up last is the one the reader is looking at.
     *
     * A pushed screen and the screen it was pushed from are composed at the same
     * time, and the one underneath recomposes too. With a single unowned slot that
     * background recomposition handed the control to the screen underneath — the
     * shell's arrow and the pushed screen's arrow would both be on screen.
     */
    @Test
    fun the_bar_that_came_up_last_owns_the_control() {
        val holder = ScreenBackControlHolder()
        val under = Any()
        val onScreen = Any()

        holder.publish(under, ownsControl = true)
        holder.publish(onScreen, ownsControl = true)
        assertSame(onScreen, holder.owner)

        // The screen underneath republishes: its entry is replaced in place.
        holder.publish(under, ownsControl = true)
        assertSame(onScreen, holder.owner)

        // Leaving the screen on screen gives the control back to the one underneath.
        holder.release(onScreen)
        assertSame(under, holder.owner)
    }

    /**
     * A screen that draws no control of its own takes the ownership back.
     *
     * It is the screen the reader is looking at, and its answer is the one that
     * counts: the shell's control is the back control there, so the shell must
     * draw it — and the bar underneath, which is not on screen, must not.
     */
    @Test
    fun a_bar_that_draws_no_control_takes_the_ownership_back() {
        val holder = ScreenBackControlHolder()
        val under = Any()
        val onScreen = Any()

        holder.publish(under, ownsControl = true)
        holder.publish(onScreen, ownsControl = false)

        assertNull(holder.owner)
    }
}

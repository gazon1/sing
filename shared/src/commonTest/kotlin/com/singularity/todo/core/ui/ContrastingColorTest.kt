package com.singularity.todo.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The luminance threshold behind [contrastingForeground], pinned from both sides.
 *
 * The rule is one comparison — `luminance() > 0.5f` — and everything that draws on it
 * (the colour swatches, the project card, the project detail hero) reads the result
 * without reading the rule, so a threshold that drifted would change every one of them
 * at once and nothing else in the suite would notice.
 *
 * Two greys one 8-bit step apart straddle the threshold: `0xFFBBBBBB` measures 0.496933
 * and `0xFFBCBCBC` measures 0.502886 (WCAG relative luminance — the sRGB channels are
 * linearised before they are weighted). The pair is the point: a threshold that moved,
 * or a luminance computed from the raw sRGB channels instead of the linear ones, flips
 * one of the two and leaves the other alone.
 *
 * The measured luminances are asserted as well as the outcomes. Without that, a rule
 * that kept its behaviour but changed its arithmetic — raw channels instead of linear
 * ones — would still pass every outcome assertion. The tolerance is a thousandth: the
 * gap that matters is the 0.234 between a linear and a raw measurement of the same
 * grey, and the gap that must not be lost is the 0.006 between the two greys.
 *
 * **Sensitivity.** Each of these mutations turns this class red:
 *
 * - reversing the comparison (`>` becomes `<`) — both boundary backgrounds resolve to
 *   the opposite colour;
 * - swapping the black and white branches — both boundary backgrounds resolve to the
 *   opposite colour;
 * - measuring the raw sRGB channels — `0xFFBCBCBC` would measure 0.737255, and the
 *   measured-luminance assertion fails.
 *
 * To reproduce: edit [contrastingForeground], run this class, and revert. The helper
 * must end byte-identical — `git diff` on it is empty.
 */
@Tag("fast")
class ContrastingColorTest {
    /** The grey one 8-bit step above the threshold — measured luminance 0.502886. */
    private val justAboveThreshold = Color(0xFFBCBCBC)

    /** The grey one 8-bit step below the threshold — measured luminance 0.496933. */
    private val justBelowThreshold = Color(0xFFBBBBBB)

    @Test
    fun `a background one step above the threshold gets a black foreground`() {
        assertEquals(
            0.502886f,
            justAboveThreshold.luminance(),
            0.001f,
            "0xFFBCBCBC is the grey just above the 0.5 threshold, and its luminance moved",
        )

        val foreground = justAboveThreshold.contrastingForeground()
        assertEquals(
            Color.Black,
            foreground,
            "0xFFBCBCBC measures ${justAboveThreshold.luminance()}, above the 0.5 threshold, " +
                "but resolved to $foreground",
        )
    }

    @Test
    fun `a background one step below the threshold gets a white foreground`() {
        assertEquals(
            0.496933f,
            justBelowThreshold.luminance(),
            0.001f,
            "0xFFBBBBBB is the grey just below the 0.5 threshold, and its luminance moved",
        )

        val foreground = justBelowThreshold.contrastingForeground()
        assertEquals(
            Color.White,
            foreground,
            "0xFFBBBBBB measures ${justBelowThreshold.luminance()}, below the 0.5 threshold, " +
                "but resolved to $foreground",
        )
    }

    @Test
    fun `a bright background gets a black foreground`() {
        val background = Color.Yellow
        assertTrue(
            background.luminance() > 0.5f,
            "yellow is the bright case the threshold is read against, and it measures " +
                "${background.luminance()}",
        )

        val foreground = background.contrastingForeground()
        assertEquals(
            Color.Black,
            foreground,
            "yellow measures ${background.luminance()}, above the 0.5 threshold, " +
                "but resolved to $foreground",
        )
    }

    @Test
    fun `a dark background gets a white foreground`() {
        val background = Color(0xFF1E1E1E)
        assertTrue(
            background.luminance() < 0.5f,
            "0xFF1E1E1E is the dark case the threshold is read against, and it measures " +
                "${background.luminance()}",
        )

        val foreground = background.contrastingForeground()
        assertEquals(
            Color.White,
            foreground,
            "0xFF1E1E1E measures ${background.luminance()}, below the 0.5 threshold, " +
                "but resolved to $foreground",
        )
    }
}

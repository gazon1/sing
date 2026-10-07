package com.singularity.todo.core.ui.onboarding

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where the card goes when the preferred side has no room.
 *
 * A tour card drawn off the bottom of the screen is worse than a tour card in an awkward
 * place: the user sees the scrim, cannot read the explanation, and has a Skip button they
 * also cannot reach.
 */
@Tag("fast")
class SpotlightCardPlacementTest {

    private fun resolve(
        preferred: SpotlightCardPlacement,
        holeTop: Float,
        holeBottom: Float,
        cardHeight: Float = 200f,
        availableHeight: Float = 800f,
        gap: Float = 16f,
    ) = resolveCardPlacement(preferred, holeTop, holeBottom, cardHeight, availableHeight, gap)

    @Test
    fun `the preferred side wins when it has room`() {
        assertEquals(
            SpotlightCardPlacement.Below,
            resolve(SpotlightCardPlacement.Below, holeTop = 100f, holeBottom = 140f),
        )
        assertEquals(
            SpotlightCardPlacement.Above,
            resolve(SpotlightCardPlacement.Above, holeTop = 400f, holeBottom = 440f),
        )
    }

    @Test
    fun `a target at the bottom flips the card above it`() {
        // Hole at 600..640; 640 + 16 + 200 = 856 > 800, so below does not fit.
        assertEquals(
            SpotlightCardPlacement.Above,
            resolve(SpotlightCardPlacement.Below, holeTop = 600f, holeBottom = 640f),
        )
    }

    @Test
    fun `a target at the top flips the card below it`() {
        // Hole at 0..40; 0 - 16 - 200 < 0, so above does not fit.
        assertEquals(
            SpotlightCardPlacement.Below,
            resolve(SpotlightCardPlacement.Above, holeTop = 0f, holeBottom = 40f),
        )
    }

    @Test
    fun `when neither side fits the card is pinned to the bottom`() {
        // A tall card on a short screen with the hole in the middle: no side has room.
        val placement = resolve(
            preferred = SpotlightCardPlacement.Above,
            holeTop = 400f,
            holeBottom = 440f,
            cardHeight = 700f,
        )

        assertEquals(
            SpotlightCardPlacement.Below,
            placement,
            "a pinned side is a deliberate answer; overlapping the target beats drawing off-screen",
        )
    }

    @Test
    fun `a card that exactly fits is accepted`() {
        // 640 + 16 + 200 == 856, and available is 856: touching the edge is fitting.
        assertEquals(
            SpotlightCardPlacement.Below,
            resolve(
                preferred = SpotlightCardPlacement.Below,
                holeTop = 600f,
                holeBottom = 640f,
                availableHeight = 856f,
            ),
        )
    }
}

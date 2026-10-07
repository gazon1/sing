package com.singularity.todo.core.ui.onboarding

import androidx.compose.ui.geometry.Rect
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The spotlight's geometry, away from Compose.
 *
 * These are the decisions that decide whether the hole covers its target, whether the
 * card ends up on screen, and whether the morph passes through a state that renders as
 * nothing. All of them are plain arithmetic over rectangles, which is why they are here
 * and not behind a graphics stack.
 */
@Tag("fast")
class SpotlightHoleTest {

    @Test
    fun `a circle covers a wide target rather than being clipped to its height`() {
        val wide = Rect(left = 0f, top = 0f, right = 200f, bottom = 40f)

        val hole = spotlightHole(wide, SpotlightShape.Circle)

        assertEquals(200f, hole.bounds.width, "the circle must span the target's width")
        assertEquals(200f, hole.bounds.height)
        assertEquals(100f, hole.cornerRadius, "a circle's radius is half its side")
        assertTrue(hole.bounds.top <= wide.top && hole.bounds.bottom >= wide.bottom)
    }

    @Test
    fun `a circle is centred on the target`() {
        val offCentre = Rect(left = 30f, top = 10f, right = 70f, bottom = 50f)

        val hole = spotlightHole(offCentre, SpotlightShape.Circle)

        assertEquals(50f, hole.bounds.center.x, 0.01f)
        assertEquals(30f, hole.bounds.center.y, 0.01f)
    }

    @Test
    fun `a rounded hole keeps the target's own bounds`() {
        val target = Rect(left = 4f, top = 8f, right = 204f, bottom = 48f)

        val hole = spotlightHole(target, SpotlightShape.Rounded(radiusFraction = 0.25f))

        assertEquals(target, hole.bounds)
    }

    @Test
    fun `a rounded radius never exceeds half the shorter side`() {
        // A fraction above 0.5 would ask for a radius larger than the shape allows, and
        // the corner is then drawn differently from what the geometry says.
        val small = Rect(left = 0f, top = 0f, right = 40f, bottom = 40f)

        val hole = spotlightHole(small, SpotlightShape.Rounded(radiusFraction = 4f))

        assertEquals(20f, hole.cornerRadius, "the radius must clamp at half the shorter side")
    }

    @Test
    fun `expanding grows every side and the radius together`() {
        val hole = SpotlightHole(Rect(10f, 10f, 50f, 30f), cornerRadius = 4f)

        val bigger = hole.expandedBy(8f)

        assertEquals(Rect(2f, 2f, 58f, 38f), bigger.bounds)
        assertEquals(12f, bigger.cornerRadius, "the radius must grow with the padding")
    }

    @Test
    fun `the closed hole sits on the target's centre`() {
        val hole = SpotlightHole(Rect(100f, 40f, 140f, 80f), cornerRadius = 20f)

        val closed = closedHole(hole)

        assertEquals(120f, closed.bounds.center.x, 0.01f)
        assertEquals(60f, closed.bounds.center.y, 0.01f)
        assertEquals(1f, closed.bounds.width)
        assertTrue(closed.bounds.width > 0f, "a zero-size path has no fillable area and would blink")
    }

    @Test
    fun `interpolating at the endpoints returns the holes themselves`() {
        val from = closedHole(SpotlightHole(Rect(0f, 0f, 100f, 100f), 50f))
        val to = SpotlightHole(Rect(0f, 0f, 100f, 100f), 50f)

        assertEquals(from, lerpHole(from, to, 0f))
        assertEquals(to, lerpHole(from, to, 1f))
    }

    @Test
    fun `interpolating never produces a negative radius`() {
        // The closed hole has a tiny positive radius, not a negative one. If the
        // implementation ever interpolated through zero the mid-frame would be negative,
        // and a negative corner radius is not "sharp" — it is undefined to the renderer.
        val closed = closedHole(SpotlightHole(Rect(0f, 0f, 100f, 100f), 50f))
        val open = SpotlightHole(Rect(0f, 0f, 100f, 100f), 50f)

        val mid = lerpHole(closed, open, 0.5f)

        assertTrue(mid.cornerRadius >= 0f, "radius must stay non-negative through the morph")
    }

    @Test
    fun `a fraction outside zero to one is clamped rather than extrapolated`() {
        val from = SpotlightHole(Rect(0f, 0f, 10f, 10f), 5f)
        val to = SpotlightHole(Rect(100f, 100f, 200f, 200f), 50f)

        assertEquals(to, lerpHole(from, to, 2f))
        assertEquals(from, lerpHole(from, to, -1f))
    }
}
